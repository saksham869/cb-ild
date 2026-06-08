package org.mifos.creditbureau.cb_ild.controller;

import lombok.extern.slf4j.Slf4j;
import org.mifos.creditbureau.cb_ild.aop.Auditable;
import org.mifos.creditbureau.cb_ild.service.bureau.IBureauReadinessService;
import org.mifos.creditbureau.cb_ild.service.kyc.KycReadinessResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for bureau readiness check.
 *
 * Endpoint: GET /api/clients/{id}/bureau-readiness
 *
 * This is the ONLY endpoint in Phase 1.
 * Angular Tab 1 calls this to display:
 *   KYC score, ready indicator, FICO score, risk band, score drop alert.
 *
 * Security:
 *   @PreAuthorize — only KYC_OFFICER or COMPLIANCE can call this
 *   CREDIT_ANALYST cannot — they use submission endpoints
 *   JWT validated by SecurityConfig (built next)
 *
 * Audit:
 *   @Auditable fires CbildAuditAspect automatically
 *   Every call saved to audit_entry table — compliance requirement
 *
 * Error handling:
 *   All exceptions handled by GlobalExceptionHandler
 *   FineractNotFoundException   → 404 FINERACT_CLIENT_NOT_FOUND
 *   FineractConnectionException → 504 FINERACT_UNREACHABLE
 *   CdcNotConfiguredException   → 503 CDC_NOT_CONFIGURED
 *   Exception (any)             → 500 INTERNAL_ERROR
 *
 * Security — never log:
 *   RFC value, FICO score, dateOfBirth
 *   Only clientId logged
 */
@Slf4j
@RestController
@RequestMapping("/api/clients")
public class BureauReadinessController {

    private final IBureauReadinessService bureauReadinessService;

    public BureauReadinessController(
            IBureauReadinessService bureauReadinessService) {
        this.bureauReadinessService = bureauReadinessService;
    }

    /**
     * GET /api/clients/{id}/bureau-readiness
     *
     * Returns KycReadinessResult for Angular Tab 1.
     *
     * @param id Fineract client ID
     * @return 200 + KycReadinessResult
     *         404 if client not found in Fineract
     *         503 if CDC not configured or Fineract down
     *         422 handled by GlobalExceptionHandler (not thrown here)
     */
    @GetMapping("/{id}/bureau-readiness")
    @PreAuthorize("hasAnyRole('KYC_OFFICER', 'COMPLIANCE')")
    @Auditable(action = "BUREAU_READINESS_CHECK", entityType = "BureauResponse")
    public ResponseEntity<KycReadinessResult> getBureauReadiness(
            @PathVariable Long id) {

        log.info("Bureau readiness request — clientId: {}", id);

        KycReadinessResult result =
                bureauReadinessService.checkReadiness(id);

        return ResponseEntity.ok(result);
    }
}
