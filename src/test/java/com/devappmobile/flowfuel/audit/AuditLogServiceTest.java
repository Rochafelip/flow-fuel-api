package com.devappmobile.flowfuel.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock private AuditLogRepository auditLogRepository;
    @Mock private HttpServletRequest httpServletRequest;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository);
        lenient().when(httpServletRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        lenient().when(httpServletRequest.getRemoteAddr()).thenReturn("198.51.100.7");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(httpServletRequest));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void record_persisteComUserIdAcaoEIp() {
        auditLogService.record(42L, AuditAction.LOGIN);

        verify(auditLogRepository).save(argThat(log ->
                log.getUserId().equals(42L)
                        && log.getAction() == AuditAction.LOGIN
                        && log.getIpAddress().equals("198.51.100.7")));
    }

    @Test
    void record_semRequestAttributes_naoLancaENaoPersiste() {
        RequestContextHolder.resetRequestAttributes();

        assertThatNoException().isThrownBy(() -> auditLogService.record(1L, AuditAction.LOGIN));
        verify(auditLogRepository).save(argThat(log -> log.getIpAddress() == null));
    }

    @Test
    void record_quandoRepositorioFalha_naoPropagaExcecao() {
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException("Redis/DB indisponível"));

        assertThatNoException().isThrownBy(() -> auditLogService.record(1L, AuditAction.LOGIN));
    }
}
