package org.mifos.creditbureau.cb_ild.controller;

import lombok.extern.slf4j.Slf4j;
import org.mifos.creditbureau.cb_ild.entity.DisputeCase;
import org.mifos.creditbureau.cb_ild.repository.DisputeCaseRepository;
import org.mifos.creditbureau.cb_ild.service.dispute.IDisputeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the dispute workflow (MX-275).
 *
 * POST /api/disputes              - raise a new dispute
 * PUT  /api/disputes/{id}/status  - advance state machine
 * GET  /api/disputes/{id}         - view a dispute
 *
 * @PreAuthorize dormant until MX-276 adds @EnableMethodSecurity.
 * Error handling via GlobalExceptionHandler.
 */
@Slf4j
@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    private final IDisputeService disputeService;
    private final DisputeCaseRepository disputeCaseRepository;

    public DisputeController(
            IDisputeService disputeService,
            DisputeCaseRepository disputeCaseRepository) {
        this.disputeService = disputeService;
        this.disputeCaseRepository = disputeCaseRepository;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('KYC_OFFICER', 'CREDIT_ANALYST', 'COMPLIANCE')")
    public ResponseEntity<DisputeCase> createDispute(
            @RequestBody CreateDisputeRequest request) {
        log.info("Create dispute - submissionRecordId: {}",
                request.submissionRecordId());
        DisputeCase dispute = disputeService.createDispute(
                request.submissionRecordId(),
                request.disputeDetails(),
                request.raisedBy());
        return ResponseEntity.ok(dispute);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('KYC_OFFICER', 'CREDIT_ANALYST', 'COMPLIANCE')")
    public ResponseEntity<DisputeCase> updateStatus(
            @PathVariable Long id,
            @RequestBody UpdateStatusRequest request) {
        log.info("Update dispute status - id: {}, newStatus: {}",
                id, request.newStatus());
        DisputeCase updated = disputeService.updateStatus(
                id, request.newStatus(), request.resolutionNotes());
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('KYC_OFFICER', 'CREDIT_ANALYST', 'COMPLIANCE')")
    public ResponseEntity<DisputeCase> getDispute(
            @PathVariable Long id) {
        log.info("Get dispute - id: {}", id);
        DisputeCase dispute = disputeCaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "DisputeCase not found: " + id));
        return ResponseEntity.ok(dispute);
    }

    public record CreateDisputeRequest(
            Long submissionRecordId,
            String disputeDetails,
            String raisedBy) {}

    public record UpdateStatusRequest(
            String newStatus,
            String resolutionNotes) {}
}
