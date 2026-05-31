package org.mifos.creditbureau.cb_ild.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mifos.creditbureau.cb_ild.entity.AuditEntry;
import org.mifos.creditbureau.cb_ild.repository.AuditEntryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CbildAuditAspect.
 *
 * Test 1: @Auditable method succeeds → AuditEntry saved with SUCCESS
 * Test 2: @Auditable method throws → AuditEntry saved with FAILURE
 * Test 3: @Auditable method throws → exception rethrown
 * Test 4: action from annotation used when provided
 * Test 5: method name used as action when annotation action empty
 */
@ExtendWith(MockitoExtension.class)
class CbildAuditAspectTest {

    @Mock
    private AuditEntryRepository auditEntryRepository;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private Signature signature;

    @Mock
    private Auditable auditable;

    private CbildAuditAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new CbildAuditAspect(auditEntryRepository);
    }

    @Test
    @DisplayName("@Auditable method succeeds — AuditEntry saved with SUCCESS")
    void audit_methodSucceeds_savesSuccessEntry() throws Throwable {
        when(auditable.action()).thenReturn("CDC_SCORE_PULL");
        when(auditable.entityType()).thenReturn("BureauResponse");
        when(joinPoint.proceed()).thenReturn("result");
        when(auditEntryRepository.save(any(AuditEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Object result = aspect.audit(joinPoint, auditable);

        assertThat(result).isEqualTo("result");
        verify(auditEntryRepository).save(any(AuditEntry.class));
    }

    @Test
    @DisplayName("@Auditable method throws — AuditEntry saved with FAILURE")
    void audit_methodThrows_savesFailureEntry() throws Throwable {
        when(auditable.action()).thenReturn("CDC_SCORE_PULL");
        when(auditable.entityType()).thenReturn("BureauResponse");
        when(joinPoint.proceed())
                .thenThrow(new RuntimeException("CDC failed"));
        when(auditEntryRepository.save(any(AuditEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> aspect.audit(joinPoint, auditable))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("CDC failed");

        verify(auditEntryRepository).save(any(AuditEntry.class));
    }

    @Test
    @DisplayName("@Auditable method throws — exception always rethrown")
    void audit_methodThrows_exceptionAlwaysRethrown() throws Throwable {
        when(auditable.action()).thenReturn("TEST");
        when(auditable.entityType()).thenReturn("Test");
        when(joinPoint.proceed())
                .thenThrow(new IllegalStateException("must rethrow"));
        when(auditEntryRepository.save(any(AuditEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> aspect.audit(joinPoint, auditable))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("must rethrow");
    }

    @Test
    @DisplayName("action from annotation used when provided")
    void audit_actionFromAnnotation_usedWhenProvided() throws Throwable {
        when(auditable.action()).thenReturn("MY_ACTION");
        when(auditable.entityType()).thenReturn("MyEntity");
        when(joinPoint.proceed()).thenReturn(null);
        when(auditEntryRepository.save(any(AuditEntry.class)))
                .thenAnswer(inv -> {
                    AuditEntry entry = inv.getArgument(0);
                    assertThat(entry.getAction()).isEqualTo("MY_ACTION");
                    return entry;
                });

        aspect.audit(joinPoint, auditable);

        verify(auditEntryRepository).save(any(AuditEntry.class));
    }

    @Test
    @DisplayName("method name used as action when annotation action empty")
    void audit_methodNameUsed_whenAnnotationActionEmpty() throws Throwable {
        when(auditable.action()).thenReturn("");
        when(auditable.entityType()).thenReturn("");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("pullAndSave");
        when(joinPoint.proceed()).thenReturn(null);
        when(auditEntryRepository.save(any(AuditEntry.class)))
                .thenAnswer(inv -> {
                    AuditEntry entry = inv.getArgument(0);
                    assertThat(entry.getAction()).isEqualTo("pullAndSave");
                    return entry;
                });

        aspect.audit(joinPoint, auditable);

        verify(auditEntryRepository).save(any(AuditEntry.class));
    }
}
