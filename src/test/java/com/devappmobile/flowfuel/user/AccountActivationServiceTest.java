package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.audit.AuditAction;
import com.devappmobile.flowfuel.audit.AuditLogService;
import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.security.OpaqueTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountActivationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ActivationTokenRepository tokenRepository;
    @Mock private AccountActivationNotifier notifier;
    @Mock private TokenIssuer tokenIssuer;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private AccountActivationService accountActivationService;

    private User pendingUser;

    @BeforeEach
    void setUp() {
        pendingUser = new User("pendente@example.com", "hashed", "Pendente");
        pendingUser.setId(5L);
        pendingUser.setStatus(UserStatus.PENDING_ACTIVATION);
    }

    @Test
    void activate_comTokenValido_ativaContaERetornaTokenPair() {
        String plaintext = "plain-token";
        ActivationToken token = new ActivationToken(pendingUser,
                OpaqueTokenGenerator.sha256(plaintext), LocalDateTime.now().plusMinutes(30));
        TokenPairResponse expected = new TokenPairResponse("access", "refresh", 900L);

        when(tokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext)))
                .thenReturn(Optional.of(token));
        when(tokenIssuer.issueTokenPair(pendingUser)).thenReturn(expected);

        TokenPairResponse response = accountActivationService.activate(plaintext);

        assertThat(response).isEqualTo(expected);
        assertThat(pendingUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(token.isUsed()).isTrue();
        verify(userRepository).save(pendingUser);
        verify(auditLogService).record(pendingUser.getId(), AuditAction.ACCOUNT_ACTIVATION);
    }

    @Test
    void activate_comTokenInexistente_lancaAuthActivationInvalid() {
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountActivationService.activate("token-inexistente"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenIssuer, auditLogService);
    }

    @Test
    void activate_comTokenJaUsado_lancaAuthActivationInvalid() {
        String plaintext = "usado-token";
        ActivationToken token = new ActivationToken(pendingUser,
                OpaqueTokenGenerator.sha256(plaintext), LocalDateTime.now().plusMinutes(30));
        token.setUsedAt(LocalDateTime.now().minusMinutes(1));

        when(tokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> accountActivationService.activate(plaintext))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenIssuer, auditLogService);
    }

    @Test
    void activate_comTokenExpirado_lancaAuthActivationInvalid() {
        String plaintext = "expirado-token";
        ActivationToken token = new ActivationToken(pendingUser,
                OpaqueTokenGenerator.sha256(plaintext), LocalDateTime.now().minusMinutes(1));

        when(tokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> accountActivationService.activate(plaintext))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenIssuer, auditLogService);
    }

    @Test
    void activate_comTokenAusente_lancaAuthActivationInvalid() {
        assertThatThrownBy(() -> accountActivationService.activate(""))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenRepository, tokenIssuer, auditLogService);
    }
}
