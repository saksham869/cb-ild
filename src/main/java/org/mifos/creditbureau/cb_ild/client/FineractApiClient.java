package org.mifos.creditbureau.cb_ild.client;

import lombok.extern.slf4j.Slf4j;
import org.mifos.creditbureau.cb_ild.exception.FineractConnectionException;
import org.mifos.creditbureau.cb_ild.exception.FineractNotFoundException;
import org.mifos.creditbureau.cb_ild.exception.FineractServerException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Calls Apache Fineract to fetch client data needed for CDC submissions.
 *
 * Fixes 4 confirmed bugs from the plugin ClientApiService:
 *   Bug 1: Never called /identifiers — RFC always null — CDC-001 every time
 *   Bug 2: static RestTemplate — no timeouts — threads hang forever
 *   Bug 3: /addresses throws on 404 — should return null gracefully
 *   Bug 4: ClientData had no nationalId field — RFC never stored
 *
 * RFC strategy:
 *   Primary:  GET /clients/{id}/identifiers where documentType=NATIONAL_ID
 *   Fallback: externalId from GET /clients/{id} basic call
 *   If both null: nationalId=null in FineractClientData
 *
 * SECURITY:
 *   Never logs nationalId (RFC) — PII
 *   Never logs dateOfBirth — PII
 *   Logs only clientId and operation names
 */
@Slf4j
@Component
public class FineractApiClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String tenantId;

    // Reusable type references — avoids creating anonymous class on every call
    private static final ParameterizedTypeReference<List<FineractIdentifierDTO>>
            IDENTIFIER_LIST_TYPE = new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<FineractAddressDTO>>
            ADDRESS_LIST_TYPE = new ParameterizedTypeReference<>() {};

    /**
     * Constructor injection — never @Autowired on fields.
     * @Qualifier selects the fineractRestTemplate bean specifically.
     * Trailing slash stripped from baseUrl to prevent double-slash URLs.
     */
    public FineractApiClient(
            @Qualifier("fineractRestTemplate") RestTemplate restTemplate,
            @Value("${mifos.fineract.api.base-url}") String baseUrl,
            @Value("${mifos.fineract.api.tenant-id}") String tenantId) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.tenantId = tenantId;
    }

    /**
     * Fetches complete client data from all 3 Fineract endpoints.
     *
     * @param clientId Fineract client ID — must not be null
     * @return FineractClientData with all available fields populated
     * @throws IllegalArgumentException if clientId is null
     * @throws FineractNotFoundException if client does not exist (404)
     * @throws FineractServerException if Fineract returns 5xx
     * @throws FineractConnectionException if Fineract is unreachable
     */
    public FineractClientData getClientData(Long clientId) {
        if (clientId == null) {
            throw new IllegalArgumentException(
                    "clientId must not be null");
        }

        log.info("Fetching client data from Fineract for clientId: {}",
                clientId);

        // Step 1: Basic client data — throws if client not found
        FineractClientResponse basicClient = fetchBasicClient(clientId);

        // Step 2: RFC from /identifiers — null if not found, never throws
        String nationalId = extractRfc(clientId, basicClient.externalId());

        // Step 3: Address from /addresses — null if 404, never throws
        FineractAddressDTO address = extractPrimaryAddress(clientId);

        // Step 4: Log result without PII — only presence/absence
        log.info("Fineract data fetched for clientId: {} nationalId present: {} address present: {}",
                clientId, nationalId != null, address != null);

        return buildClientData(clientId, basicClient, nationalId, address);
    }

    /**
     * Calls GET /clients/{id} for basic client information.
     * Only call that throws on failure — without basic data nothing works.
     */
    private FineractClientResponse fetchBasicClient(Long clientId) {
        String url = baseUrl + "/clients/" + clientId;
        log.debug("Calling Fineract GET /clients/{}", clientId);

        try {
            ResponseEntity<FineractClientResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    buildHttpEntity(),
                    FineractClientResponse.class
            );

            FineractClientResponse body = response.getBody();
            if (body == null) {
                log.error("Fineract returned empty body for clientId: {}",
                        clientId);
                // 502 Bad Gateway — Fineract returned 200 but with empty body
                throw new FineractServerException(502);
            }
            return body;

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Client not found in Fineract, clientId: {}", clientId);
            throw new FineractNotFoundException(clientId);

        } catch (HttpServerErrorException e) {
            log.error("Fineract server error for clientId: {}, status: {}",
                    clientId, e.getStatusCode().value());
            throw new FineractServerException(e.getStatusCode().value());

        } catch (ResourceAccessException e) {
            log.error("Cannot connect to Fineract for clientId: {}",
                    clientId);
            throw new FineractConnectionException(e.getMessage());
        }
    }

    /**
     * Calls GET /clients/{id}/identifiers to extract RFC.
     *
     * NEVER throws — empty result or any HTTP failure returns null.
     * Caller falls back to externalId if this returns null.
     *
     * SECURITY: Never logs the RFC value — only presence/absence.
     */
    private String extractRfc(Long clientId, String externalIdFallback) {
        String url = baseUrl + "/clients/" + clientId + "/identifiers";
        log.debug("Calling Fineract GET /clients/{}/identifiers", clientId);

        try {
            ResponseEntity<List<FineractIdentifierDTO>> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            buildHttpEntity(),
                            IDENTIFIER_LIST_TYPE
                    );

            List<FineractIdentifierDTO> identifiers = response.getBody();

            if (identifiers == null || identifiers.isEmpty()) {
                log.debug("No identifiers for clientId: {}, trying externalId",
                        clientId);
                return validateRfc(externalIdFallback);
            }

            Optional<FineractIdentifierDTO> nationalId = identifiers.stream()
                    .filter(FineractIdentifierDTO::isNationalId)
                    .findFirst();

            if (nationalId.isPresent()) {
                log.debug("RFC found in identifiers for clientId: {}",
                        clientId);
                return nationalId.get().documentKey();
            }

            log.debug("No NATIONAL_ID for clientId: {}, trying externalId",
                    clientId);
            return validateRfc(externalIdFallback);

        } catch (HttpClientErrorException e) {
            log.warn("Identifiers call failed for clientId: {} status: {}, " +
                    "using externalId fallback",
                    clientId, e.getStatusCode().value());
            return validateRfc(externalIdFallback);

        } catch (HttpServerErrorException e) {
            log.warn("Identifiers server error for clientId: {} status: {}, " +
                    "using externalId fallback",
                    clientId, e.getStatusCode().value());
            return validateRfc(externalIdFallback);

        } catch (ResourceAccessException e) {
            log.warn("Identifiers timeout for clientId: {}, " +
                    "using externalId fallback", clientId);
            return validateRfc(externalIdFallback);
        }
    }

    /**
     * Calls GET /clients/{id}/addresses to get primary address.
     *
     * NEVER throws — 404 or any failure returns null.
     * Confirmed: sandbox returns 404 for /addresses (Bug 3 fix).
     */
    private FineractAddressDTO extractPrimaryAddress(Long clientId) {
        String url = baseUrl + "/clients/" + clientId + "/addresses";
        log.debug("Calling Fineract GET /clients/{}/addresses", clientId);

        try {
            ResponseEntity<List<FineractAddressDTO>> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            buildHttpEntity(),
                            ADDRESS_LIST_TYPE
                    );

            List<FineractAddressDTO> addresses = response.getBody();

            if (addresses == null || addresses.isEmpty()) {
                log.debug("No addresses found for clientId: {}", clientId);
                return null;
            }

            return addresses.stream()
                    .filter(FineractAddressDTO::hasMinimumData)
                    .findFirst()
                    .orElse(addresses.get(0));

        } catch (HttpClientErrorException.NotFound e) {
            // 404 expected on sandbox — not an error
            log.debug("No addresses for clientId: {} (404)", clientId);
            return null;

        } catch (HttpClientErrorException e) {
            log.warn("Addresses call failed for clientId: {} status: {}, " +
                    "proceeding without address",
                    clientId, e.getStatusCode().value());
            return null;

        } catch (HttpServerErrorException e) {
            log.warn("Addresses server error for clientId: {} status: {}, " +
                    "proceeding without address",
                    clientId, e.getStatusCode().value());
            return null;

        } catch (ResourceAccessException e) {
            log.warn("Addresses timeout for clientId: {}, " +
                    "proceeding without address", clientId);
            return null;
        }
    }

    /**
     * Builds HTTP entity with required Fineract headers.
     * fineract-platform-tenantid is required on every call.
     * Basic Auth handled by RestTemplate bean in RestTemplateConfig.
     */
    private HttpEntity<Void> buildHttpEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("fineract-platform-tenantid", tenantId);
        return new HttpEntity<>(headers);
    }

    /**
     * Validates RFC string — returns null if null or blank.
     * Both null and blank cause CDC-001.
     * NEVER logs the RFC value.
     */
    private String validateRfc(String rfc) {
        if (rfc == null || rfc.isBlank()) {
            return null;
        }
        return rfc;
    }

    /**
     * Assembles FineractClientData from all 3 endpoint responses.
     * Maps Fineract field names (firstname/lastname lowercase n)
     * to CB-ILD domain names (firstName/lastName camelCase).
     */
    private FineractClientData buildClientData(
            long clientId,
            FineractClientResponse basic,
            String nationalId,
            FineractAddressDTO address) {

        return new FineractClientData(
                clientId,
                basic.firstname(),
                basic.lastname(),
                nationalId,
                basic.dateOfBirth(),
                basic.mobileNo(),
                basic.emailAddress(),
                address != null ? address.addressLine1() : null,
                address != null ? address.addressLine2() : null,
                address != null ? address.addressLine3() : null,
                address != null ? address.city() : null,
                address != null ? address.stateName() : null,
                address != null ? address.postalCode() : null,
                address != null ? address.countryName() : null
        );
    }
}
