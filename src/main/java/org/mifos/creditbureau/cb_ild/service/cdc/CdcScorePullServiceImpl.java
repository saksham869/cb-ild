package org.mifos.creditbureau.cb_ild.service.cdc;

import lombok.extern.slf4j.Slf4j;
import org.mifos.creditbureau.cb_ild.entity.BureauResponseEntity;
import org.mifos.creditbureau.cb_ild.repository.BureauResponseRepository;
import org.mifos.creditbureau.cb_ild.exception.CdcNotConfiguredException;
import org.mifos.creditbureau.cb_ild.aop.Auditable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Implementation of ICdcScorePullService.
 *
 * Mock mode (mifos.cdc.mock.enabled=true):
 *   Saves ficoScore=750 — no external calls made.
 *   Safe for development and CI.
 *
 * Real mode (Phase 2 — pending Yu Wati + Victor confirmation):
 *   Will call plugin → real CDC → map response → save.
 *
 * Security:
 *   No PII logged — clientId only, never RFC or score value.
 *   rawResponseHash = SHA-256 fingerprint, never raw response.
 *   fullResponse = null in mock mode.
 *
 * Compliance:
 *   expiryDate = dateOfFirstDelinquency + 72 months (LRSIC rule).
 *   Never hard-delete — softDeleted=true only.
 *
 * Frontend:
 *   ficoScore, riskBand, scoreDropAlert → Angular Tab 1 + Tab 3
 *   hasDelinquencies, dateOfFirstDelinquency → Angular Tab 3
 */
@Slf4j
@Service
public class CdcScorePullServiceImpl implements ICdcScorePullService {

    private static final String BUREAU_TYPE = "CIRCULO_DE_CREDITO";

    // Mock FICO score — realistic value in Very Good range (740-799)
    // Maps to riskBand=LOW for Angular Tab 1 color badge
    private static final int MOCK_FICO_SCORE = 750;
    private static final String MOCK_RISK_BAND = "LOW";

    private final BureauResponseRepository repository;
    private final boolean mockEnabled;

    // Constructor injection — never @Autowired on fields
    public CdcScorePullServiceImpl(
            BureauResponseRepository repository,
            @Value("${mifos.cdc.mock.enabled:true}") boolean mockEnabled) {
        this.repository = repository;
        this.mockEnabled = mockEnabled;
        log.info("CdcScorePullServiceImpl initialized — mockEnabled: {}",
                mockEnabled);
    }

    /**
     * Pull CDC score for client and save to bureau_response table.
     *
     * Step 1: Validate clientId
     * Step 2: Check mock mode
     * Step 3: Check previous score
     * Step 4: Score drop detection
     * Step 5: Compute SHA-256 hash
     * Step 6: Build + save entity
     */
    @Override
    @Auditable(action = "CDC_SCORE_PULL", entityType = "BureauResponse")
    @Transactional
    public BureauResponseEntity pullAndSave(Long clientId) {

        // Step 1 — Validate clientId
        if (clientId == null) {
            throw new IllegalArgumentException("clientId must not be null");
        }

        log.info("Starting CDC score pull for clientId: {}", clientId);

        if (mockEnabled) {
            return pullAndSaveMock(clientId);
        }

        // Phase 2 — real CDC call (not implemented yet)
        // Waiting for: Yu Wati endpoint confirmation + CDC credentials
        throw new CdcNotConfiguredException();
    }

    /**
     * Mock mode implementation.
     * Saves ficoScore=750 to bureau_response — no external calls.
     */
    private BureauResponseEntity pullAndSaveMock(Long clientId) {

        // Step 3 — Check previous score for score drop detection
        Optional<BureauResponseEntity> previous =
                repository.findTopByClientIdOrderByPulledAtDesc(clientId);

        // Step 4 — Score drop detection
        boolean scoreDropAlert = isScoreDrop(previous, MOCK_FICO_SCORE);

        if (scoreDropAlert) {
            log.info("Score drop detected for clientId: {} — alert set",
                    clientId);
        }

        // Step 5 — SHA-256 hash of mock response
        String rawResponseHash = sha256("mock-response-" + clientId);

        // Step 6 — Build entity
        BureauResponseEntity entity = BureauResponseEntity.builder()
                .clientId(clientId)
                .bureauType(BUREAU_TYPE)
                .ficoScore(MOCK_FICO_SCORE)
                .riskBand(MOCK_RISK_BAND)
                .scoreDropAlert(scoreDropAlert)
                .rawResponseHash(rawResponseHash)
                .hasDelinquencies(false)
                .softDeleted(false)
                .fullResponse(null)
                .expiryDate(null)
                .dateOfFirstDelinquency(null)
                .build();

        // Step 6 — Save to DB
        BureauResponseEntity saved = repository.save(entity);

        log.info("CDC score pull complete for clientId: {}", clientId);

        return saved;
    }

    /**
     * Score drop detection.
     * Returns true if new score is lower than previous score.
     * Angular Tab 3 — warning banner shown when true.
     */
    private boolean isScoreDrop(
            Optional<BureauResponseEntity> previous,
            Integer newScore) {

        if (previous.isEmpty()) return false;
        if (newScore == null) return false;

        Integer previousScore = previous.get().getFicoScore();
        if (previousScore == null) return false;

        return newScore < previousScore;
    }

    /**
     * Compute SHA-256 hash of input string.
     * Used for rawResponseHash — deduplication without storing PII.
     *
     * @param input string to hash
     * @return 64-character hex string
     */
    private String sha256(String input) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 algorithm not available", e);
        }
    }
}
