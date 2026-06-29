package org.mifos.creditbureau.cb_ild.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CbildAuditAspect.
 *
 * Now mocks AuditPersistenceService (not AuditEntryRepository directly).
 * This matches the production design: aspect delegates to service bean
 * so @Transactional(REQUIRES_NEW) fires through Spring's proxy.
 *
 * Test 1: @Auditable method succeeds — saveAuditEntry called with SUCCESS
 * Test 2: @Auditable method throws — saveAuditEntry called with FAILURE
 * Test 3: @Auditable method throws — exception always rethrown
 * Test 4: action from annotation used when provided
 * Test 5: method name used as action when annotation action empty
 */
@ExtendWith(MockitoExtension.class)
class CbildAuditAspectTest {

    @Mock
    private AuditPersistenceService auditPersistenceService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private Signature signature;

    @Mock
    private Auditable auditable;

    private CbildAuditAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new CbildAuditAspect(auditPersistenceService);
    }

    @Test
    @DisplayName("@Auditable method succeeds — saveAuditEntry called with SUCCESS")
    void audit_methodSucceeds_savesSuccessEntry() throws Throwable {
        when(auditable.action()).thenReturn("CDC_SCORE_PULL");
        when(auditable.entityType()).thenReturn("BureauResponse");
        when(joinPoint.proceed()).thenReturn("result");

        Object result = aspect.audit(joinPoint, auditable);

        assertThat(result).isEqualTo("result");
        verify(auditPersistenceService).saveAuditEntry(
                eq("CDC_SCORE_PULL"),
                eq("BureauResponse"),
                any(), any(), any(Long.class),
                eq("SUCCESS"),
                eq(null));
    }

    @Test
    @DisplayName("@Auditable method throws — saveAuditEntry called with FAILURE")
    void audit_methodThrows_savesFailureEntry() throws Throwable {
        when(auditable.action()).thenReturn("CDC_SCORE_PULL");
        when(auditable.entityType()).thenReturn("BureauResponse");
        when(joinPoint.proceed())
                .thenThrow(new RuntimeException("CDC failed"));

        assertThatThrownBy(() -> aspect.audit(joinPoint, auditable))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("CDC failed");

        verify(auditPersistenceService).saveAuditEntry(
                eq("CDC_SCORE_PULL"),
                eq("BureauResponse"),
                any(), any(), any(Long.class),
                eq("FAILURE"),
                eq("CDC failed"));
    }

    @Test
    @DisplayName("@Auditable method throws — exception always rethrown")
    void audit_methodThrows_exceptionAlwaysRethrown() throws Throwable {
        when(auditable.action()).thenReturn("TEST");
        when(auditable.entityType()).thenReturn("Test");
        when(joinPoint.proceed())
                .thenThrow(new IllegalStateException("must rethrow"));

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

        aspect.audit(joinPoint, auditable);

        ArgumentCaptor<String> actionCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(auditPersistenceService).saveAuditEntry(
                actionCaptor.capture(),
                any(), any(), any(), any(Long.class), any(), any());

        assertThat(actionCaptor.getValue()).isEqualTo("MY_ACTION");
    }

    @Test
    @DisplayName("method name used as action when annotation action empty")
    void audit_methodNameUsed_whenAnnotationActionEmpty() throws Throwable {
        when(auditable.action()).thenReturn("");
        when(auditable.entityType()).thenReturn("");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("pullAndSave");
        when(joinPoint.proceed()).thenReturn(null);

        aspect.audit(joinPoint, auditable);

        ArgumentCaptor<String> actionCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(auditPersistenceService).saveAuditEntry(
                actionCaptor.capture(),
                any(), any(), any(), any(Long.class), any(), any());

        assertThat(actionCaptor.getValue()).isEqualTo("pullAndSave");
    }
}
