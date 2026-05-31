package org.mifos.creditbureau.cb_ild.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when KYC prerequisite not met — RFC missing.
 *
 * HTTP 422 to Angular.
 * NEVER retry — RFC must be added to Fineract first.
 *
 * This is the core Bug 1 scenario:
 *   Client has no NATIONAL_ID identifier in Fineract
 *   → RFC is null
 *   → CDC cannot match client
 *   → Throw immediately — never call CDC
 *
 * Angular Tab 1 shows:
 *   "RFC/National ID missing — add identifier in Fineract first"
 *
 * Security:
 *   missingField = "nationalId" — field name only, never actual value
 *   clientId stored internally — never in Angular message
 *   Message is generic — never exposes actual RFC value
 */
@Slf4j
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class KycPrerequisiteException extends RuntimeException {

    private final Long clientId;
    private final String missingField;

    public KycPrerequisiteException(Long clientId, String missingField) {
        super("KYC prerequisite not met — RFC/National ID required before CDC submission");
        this.clientId = clientId;
        this.missingField = missingField;
        log.warn("KYC prerequisite failed for clientId: {} — missing field: {}",
                clientId, missingField);
    }

    public Long getClientId() {
        return clientId;
    }

    public String getMissingField() {
        return missingField;
    }
}
