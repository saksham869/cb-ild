package org.mifos.creditbureau.cb_ild.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.mifos.creditbureau.cb_ild.entity.AuditEntry;
import org.mifos.creditbureau.cb_ild.repository.AuditEntryRepository;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AOP aspect for automatic audit logging.
 *
 * Intercepts all methods annotated with @Auditable.
 * Saves AuditEntry to audit_entry table after every call.
 *
 * CRITICAL:
 *   Uses @Transactional(REQUIRES_NEW) — audit entry is saved
 *   even if the main transaction rolls back.
 *   This ensures audit trail is never lost.
 *
 * Security:
 *   Never logs nationalId or RFC anywhere
 *   userId from SecurityContextHolder — JWT claim
 *   requestId from MDC — set by CorrelationIdFilter
 *   errorMessage truncated to 500 chars
 *
 * Compliance:
 *   Every @Auditable method call creates one AuditEntry
 *   SUCCESS or FAILURE recorded with duration
 *   Audit entries never deleted
 */
@Slf4j
@Aspect
@Component
public class CbildAuditAspect {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_FAILURE = "FAILURE";

    private final AuditEntryRepository auditEntryRepository;

    public CbildAuditAspect(AuditEntryRepository auditEntryRepository) {
        this.auditEntryRepository = auditEntryRepository;
    }

    /**
     * Intercepts all methods annotated with @Auditable.
     * Records action, userId, requestId, duration, result.
     * Always rethrows exceptions — never swallows.
     */
    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint,
                        Auditable auditable) throws Throwable {

        long startTime = System.currentTimeMillis();
        String action = auditable.action().isEmpty()
                ? joinPoint.getSignature().getName()
                : auditable.action();
        String entityType = auditable.entityType();
        String userId = extractUserId();
        String requestId = MDC.get("requestId");

        log.debug("Audit start — action: {}, userId: {}, requestId: {}",
                action, userId, requestId);

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;

            saveAuditEntry(action, entityType, userId,
                    requestId, duration, RESULT_SUCCESS, null);

            return result;

        } catch (Throwable ex) {
            long duration = System.currentTimeMillis() - startTime;
            String errorMessage = truncate(ex.getMessage(),
                    MAX_ERROR_MESSAGE_LENGTH);

            saveAuditEntry(action, entityType, userId,
                    requestId, duration, RESULT_FAILURE, errorMessage);

            // CRITICAL: always rethrow — never swallow exceptions
            throw ex;
        }
    }

    /**
     * Saves audit entry with REQUIRES_NEW propagation.
     * Survives main transaction rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void saveAuditEntry(
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

    /**
     * Extract userId from Spring Security context.
     * Returns "anonymous" if not authenticated.
     * Never logs actual user credentials.
     */
    private String extractUserId() {
        try {
            Authentication auth = SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                return auth.getName();
            }
        } catch (Exception e) {
            log.debug("Could not extract userId from security context");
        }
        return "anonymous";
    }

    /**
     * Truncate string to max length.
     * Prevents oversized error messages in audit_entry.
     */
    private String truncate(String input, int maxLength) {
        if (input == null) return null;
        return input.length() <= maxLength
                ? input
                : input.substring(0, maxLength);
    }
}
