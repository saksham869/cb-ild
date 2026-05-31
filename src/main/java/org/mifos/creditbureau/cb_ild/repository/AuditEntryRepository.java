package org.mifos.creditbureau.cb_ild.repository;

import org.mifos.creditbureau.cb_ild.entity.AuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for audit_entry table.
 *
 * No soft delete queries — audit entries are NEVER deleted.
 * All entries always visible — compliance requirement.
 *
 * Used by:
 *   CbildAuditAspect — save audit entry after every @Auditable method
 *   Angular Tab 5 — dispute history + audit trail
 */
@Repository
public interface AuditEntryRepository
        extends JpaRepository<AuditEntry, Long> {

    /**
     * All audit entries for a specific record — newest first.
     * Used by Angular Tab 5 — audit trail display.
     */
    List<AuditEntry> findAllByRecordIdOrderByCreatedAtDesc(Long recordId);

    /**
     * All audit entries by a specific user.
     * Used for compliance reporting.
     */
    List<AuditEntry> findAllByPerformedByOrderByCreatedAtDesc(
            String performedBy);
}
