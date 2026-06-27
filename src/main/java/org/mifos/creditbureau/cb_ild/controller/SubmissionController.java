package org.mifos.creditbureau.cb_ild.controller;

import lombok.extern.slf4j.Slf4j;
import org.mifos.creditbureau.cb_ild.aop.Auditable;
import org.mifos.creditbureau.cb_ild.entity.SubmissionRecord;
import org.mifos.creditbureau.cb_ild.repository.SubmissionRecordRepository;
import org.mifos.creditbureau.cb_ild.service.submission.BatchSubmissionAck;
import org.mifos.creditbureau.cb_ild.service.submission.ISubmissionService;
import org.mifos.creditbureau.cb_ild.service.submission.SubmissionRecordResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.PagedModel;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for the CDC submission pipeline (MX-273).
 *
 * Endpoints:
 *   POST /api/submissions/run     — start a batch submission run
 *   GET  /api/submissions/history — paginated submission history for a client
 *
 * Angular Tab 2 (Reporting Dashboard, Phase 3) calls both.
 *
 * Security:
 *   @PreAuthorize — CREDIT_ANALYST or COMPLIANCE, per the RBAC table in the
 *   system prompt (Section 3.4). Same dormant-annotation pattern as
 *   BureauReadinessController: SecurityConfig is permitAll() (Phase 1,
 *   Victor confirmed no OAuth2 on mifos-bank-1) and @EnableMethodSecurity
 *   is not yet added, so these annotations are written ahead of MX-276
 *   but not yet enforced — matches the existing Phase 1 precedent exactly.
 *
 * Audit:
 *   @Auditable fires CbildAuditAspect automatically for /run.
 *   /history is a read-only query — no @Auditable, matching the
 *   established convention that @Auditable marks state-changing or
 *   CDC-calling operations (CDC_SCORE_PULL, SUBMISSION_RUN,
 *   SUBMISSION_RETRY), not plain reads.
 *
 * Error handling:
 *   All exceptions handled by GlobalExceptionHandler.
 *   IllegalArgumentException (from ISubmissionService validation) → 400.
 *
 * Security — never log:
 *   No RFC, DOB, or FICO score ever appear in this controller's data —
 *   SubmissionRecord carries none of these fields. Only clientId and
 *   status logged, matching SubmissionServiceImpl's logging convention.
 */
@Slf4j
@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private final ISubmissionService submissionService;
    private final SubmissionRecordRepository submissionRecordRepository;

    public SubmissionController(
            ISubmissionService submissionService,
            SubmissionRecordRepository submissionRecordRepository) {
        this.submissionService = submissionService;
        this.submissionRecordRepository = submissionRecordRepository;
    }

    /**
     * POST /api/submissions/run
     *
     * Starts a batch submission run. @Async on the service side means this
     * returns 202 immediately — per-client results (ACCEPTED/REJECTED/
     * PENDING_RETRY) are not in the response body. Check
     * GET /api/submissions/history afterward for results.
     *
     * Request body: { "clientIds": [1, 2, 3] }
     *   - non-empty clientIds: those specific clients are processed,
     *     ack.clientCount = clientIds.size()
     *   - omitted or empty clientIds: every active client is processed
     *     (ISubmissionService.runBatch() resolves this internally via
     *     FineractApiClient.getAllActiveClientIds() — confirmed live,
     *     7 active clients on mifos-bank-1 as of June 2026). The
     *     controller does not duplicate this Fineract call just to
     *     report a count; ack.clientCount = 0 and the message says
     *     "all active clients" instead.
     *
     * @param request clientIds list, nullable/empty for "all active clients"
     * @return 202 Accepted + BatchSubmissionAck
     */
    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('CREDIT_ANALYST', 'COMPLIANCE')")
    @Auditable(action = "SUBMISSION_BATCH_START", entityType = "SubmissionRecord")
    public ResponseEntity<BatchSubmissionAck> runSubmissions(
            @RequestBody(required = false) RunSubmissionsRequest request) {

        List<Long> clientIds = (request == null) ? null : request.clientIds();

        log.info("Batch submission run requested — explicit clientIds: {}",
                (clientIds == null) ? 0 : clientIds.size());

        submissionService.runBatch(clientIds);

        BatchSubmissionAck ack = (clientIds == null || clientIds.isEmpty())
                ? new BatchSubmissionAck("Batch submission started for all active clients", 0)
                : new BatchSubmissionAck("Batch submission started", clientIds.size());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ack);
    }

    /**
     * GET /api/submissions/history?clientId={id}&page=0&size=20
     *
     * Paginated submission history for a client, newest first
     * (SubmissionRecordRepository.findByClientIdOrderBySubmittedAtDesc).
     *
     * Plain Page<SubmissionRecordResponse> — HATEOAS PagedModel wrapping
     * is MX-276 exit-criteria scope (system prompt Section 5, /submissions
     * /history row), not MX-273. Retrofitting Page<> → PagedModel<> later
     * is an isolated change to this one method's return type, consistent
     * with "don't stack unreviewed PRs."
     *
     * @param clientId Fineract client ID — required
     * @param page     zero-indexed page number, default 0
     * @param size     page size, default 20
     * @return 200 + Page<SubmissionRecordResponse>
     */
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('CREDIT_ANALYST', 'COMPLIANCE')")
    public ResponseEntity<PagedModel<SubmissionRecordResponse>> getSubmissionHistory(
            @RequestParam Long clientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            PagedResourcesAssembler<SubmissionRecordResponse> assembler) {

        log.info("Submission history request — clientId: {}, page: {}, size: {}",
                clientId, page, size);

        Page<SubmissionRecord> records = submissionRecordRepository
                .findByClientIdOrderBySubmittedAtDesc(clientId, PageRequest.of(page, size));

        Page<SubmissionRecordResponse> response =
                records.map(SubmissionRecordResponse::from);

        @SuppressWarnings("unchecked")
        var pagedModel = (PagedModel<SubmissionRecordResponse>)(Object) assembler.toModel(response);
        return ResponseEntity.ok(pagedModel);
    }

    /**
     * Request body for POST /api/submissions/run.
     *
     * Java 21 record (RULE 02). clientIds nullable — null/empty means
     * "all active clients" (see runSubmissions() Javadoc).
     */
    public record RunSubmissionsRequest(List<Long> clientIds) {}
}
