package org.mifos.creditbureau.cb_ild.aop;

import lombok.extern.slf4j.Slf4j;
import org.mifos.creditbureau.cb_ild.entity.AuditEntry;
import org.mifos.creditbureau.cb_ild.repository.AuditEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate @Service bean for saving audit entries.
 *
 * CRITICAL — must be a separate bean from CbildAuditAspect.
 *
 * Why: Spring @Transactional works via proxy. When a method calls
 * another method on the SAME object (this.method()), it bypasses
 * the proxy and @Transactional is ignored.
 *
 * By extracting saveAuditEntry into this separate @Service,
 * calls from CbildAuditAspect go through Spring's proxy and
 * @Transactional(REQUIRES_NEW) fires correctly.
 *
 * Compliance guarantee:
 *   Audit entry is saved in its own independent transaction.
 *   Even if the caller's main transaction rolls back,
 *   this audit entry is committed separately and never lost.
 */
@Slf4j
@Service
public class AuditPersistenceService {

    private final AuditEntryRepository auditEntryRepository;

    public AuditPersistenceService(AuditEntryRepository auditEntryRepository) {
        this.auditEntryRepository = auditEntryRepository;
    }

    /**
     * Saves audit entry in a brand-new independent transaction.
     *
     * REQUIRES_NEW: suspends any existing transaction, opens a new one,
     * commits it, then resumes the original. This entry is never rolled
     * back even if the caller's transaction fails.
     *
     * Security:
     *   errorMessage already truncated by caller — no raw PII stored
     *   performedBy = userId from JWT — never RFC or raw credential
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditEntry(
            String action,
            String entityType,
            String userId,
            String requestId,
            long durationMs,
            String result,
            String errorMessage) {

        AuditEntry entry = AuditEntry.builder()
                .action(action)
                .entityType(entityType)
                .performedBy(userId)
                .requestId(requestId)
                .durationMs(durationMs)
                .result(result)
                .errorMessage(errorMessage)
                .build();

        auditEntryRepository.save(entry);

        log.debug("Audit saved — action: {}, result: {}, duration: {}ms",
                action, result, durationMs);
    }
}
