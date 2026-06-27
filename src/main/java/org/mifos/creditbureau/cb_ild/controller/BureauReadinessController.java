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
import org.mifos.creditbureau.cb_ild.dto.BureauResponseDTO;
import org.mifos.creditbureau.cb_ild.dto.AuditEntryDTO;
import org.mifos.creditbureau.cb_ild.entity.AuditEntry;
import org.mifos.creditbureau.cb_ild.repository.AuditEntryRepository;
import org.mifos.creditbureau.cb_ild.repository.BureauResponseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.PagedModel;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

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
    private final BureauResponseRepository bureauResponseRepository;
    private final AuditEntryRepository auditEntryRepository;

    public BureauReadinessController(
            IBureauReadinessService bureauReadinessService,
            BureauResponseRepository bureauResponseRepository,
            AuditEntryRepository auditEntryRepository) {
        this.bureauReadinessService = bureauReadinessService;
        this.bureauResponseRepository = bureauResponseRepository;
        this.auditEntryRepository = auditEntryRepository;
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

    /**
     * GET /api/clients/{id}/bureau-response
     *
     * Returns latest BureauResponseDTO for a client.
     * NEVER exposes fullResponse, rawResponseHash, softDeleted.
     * Angular Tab 3 — score history + delinquency data.
     *
     * @param id Fineract client ID
     * @return 200 + BureauResponseDTO, 404 if no response found
     */
    @GetMapping("/{id}/bureau-response")
    @PreAuthorize("hasAnyRole('CREDIT_ANALYST', 'COMPLIANCE')")
    public ResponseEntity<BureauResponseDTO> getBureauResponse(
            @PathVariable Long id) {
        log.info("Bureau response request — clientId: {}", id);
        return bureauResponseRepository
                .findTopByClientIdOrderByPulledAtDesc(id)
                .map(BureauResponseDTO::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/clients/{id}/audit-trail
     *
     * Paginated audit log for a client — COMPLIANCE only.
     * Optional date range filter via startDate/endDate.
     * HATEOAS PagedModel — Angular Tab 5.
     *
     * NEVER exposes nationalId, RFC, or raw CDC response.
     *
     * @param id        Fineract client ID
     * @param startDate optional filter start (ISO datetime)
     * @param endDate   optional filter end (ISO datetime)
     * @param page      zero-indexed page, default 0
     * @param size      page size, default 20
     * @return 200 + PagedModel<AuditEntryDTO>
     */
    @GetMapping("/{id}/audit-trail")
    @PreAuthorize("hasRole('COMPLIANCE')")
    public ResponseEntity<PagedModel<AuditEntryDTO>> getAuditTrail(
            @PathVariable Long id,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            PagedResourcesAssembler<AuditEntryDTO> assembler) {

        log.info("Audit trail request — clientId: {}, startDate: {}, endDate: {}",
                id, startDate, endDate);

        Page<AuditEntry> entries;
        PageRequest pageable = PageRequest.of(page, size);

        if (startDate != null && endDate != null) {
            entries = auditEntryRepository
                    .findByClientIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                            id, startDate, endDate, pageable);
        } else {
            entries = auditEntryRepository
                    .findByClientIdOrderByCreatedAtDesc(id, pageable);
        }

        Page<AuditEntryDTO> dtoPage = entries.map(AuditEntryDTO::from);

        @SuppressWarnings("unchecked")
        var pagedModel = (PagedModel<AuditEntryDTO>)(Object) assembler.toModel(dtoPage);
        return ResponseEntity.ok(pagedModel);
    }
}
