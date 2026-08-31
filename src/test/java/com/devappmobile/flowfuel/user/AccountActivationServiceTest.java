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
import static org.mockito.ArgumentMatchers.eq;
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
    void activate_comCodigoValido_ativaContaERetornaTokenPair() {
        String plaintext = "12345";
        ActivationToken token = new ActivationToken(pendingUser,
                OpaqueTokenGenerator.sha256(plaintext), LocalDateTime.now().plusMinutes(30));
        TokenPairResponse expected = new TokenPairResponse("access", "refresh", 900L);

        when(userRepository.findByEmail(pendingUser.getEmail())).thenReturn(Optional.of(pendingUser));
        when(tokenRepository.findByUserIdAndTokenHash(pendingUser.getId(), OpaqueTokenGenerator.sha256(plaintext)))
                .thenReturn(Optional.of(token));
        when(tokenIssuer.issueTokenPair(pendingUser)).thenReturn(expected);

        TokenPairResponse response = accountActivationService.activate(pendingUser.getEmail(), plaintext);

        assertThat(response).isEqualTo(expected);
        assertThat(pendingUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(token.isUsed()).isTrue();
        verify(userRepository).save(pendingUser);
        verify(auditLogService).record(pendingUser.getId(), AuditAction.ACCOUNT_ACTIVATION);
    }

    @Test
    void activate_comEmailInexistente_lancaAuthActivationInvalid() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountActivationService.activate("ninguem@example.com", "12345"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenIssuer, auditLogService);
    }

    @Test
    void activate_comCodigoInexistente_lancaAuthActivationInvalid() {
        when(userRepository.findByEmail(pendingUser.getEmail())).thenReturn(Optional.of(pendingUser));
        when(tokenRepository.findByUserIdAndTokenHash(eq(pendingUser.getId()), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountActivationService.activate(pendingUser.getEmail(), "00000"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenIssuer, auditLogService);
    }

    @Test
    void activate_comCodigoJaUsado_lancaAuthActivationInvalid() {
        String plaintext = "54321";
        ActivationToken token = new ActivationToken(pendingUser,
                OpaqueTokenGenerator.sha256(plaintext), LocalDateTime.now().plusMinutes(30));
        token.setUsedAt(LocalDateTime.now().minusMinutes(1));

        when(userRepository.findByEmail(pendingUser.getEmail())).thenReturn(Optional.of(pendingUser));
        when(tokenRepository.findByUserIdAndTokenHash(pendingUser.getId(), OpaqueTokenGenerator.sha256(plaintext)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> accountActivationService.activate(pendingUser.getEmail(), plaintext))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenIssuer, auditLogService);
    }

    @Test
    void activate_comCodigoExpirado_lancaAuthActivationInvalid() {
        String plaintext = "11111";
        ActivationToken token = new ActivationToken(pendingUser,
                OpaqueTokenGenerator.sha256(plaintext), LocalDateTime.now().minusMinutes(1));

        when(userRepository.findByEmail(pendingUser.getEmail())).thenReturn(Optional.of(pendingUser));
        when(tokenRepository.findByUserIdAndTokenHash(pendingUser.getId(), OpaqueTokenGenerator.sha256(plaintext)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> accountActivationService.activate(pendingUser.getEmail(), plaintext))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenIssuer, auditLogService);
    }

    @Test
    void activate_comCodigoAusente_lancaAuthActivationInvalid() {
        assertThatThrownBy(() -> accountActivationService.activate(pendingUser.getEmail(), ""))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenRepository, tokenIssuer, auditLogService);
    }

    @Test
    void activate_comEmailAusente_lancaAuthActivationInvalid() {
        assertThatThrownBy(() -> accountActivationService.activate("", "12345"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenRepository, tokenIssuer, auditLogService);
    }

    @Test
    void resendActivation_quandoEnvioFalha_naoPropagaExcecaoERetornaRespostaPadrao() {
        when(userRepository.findByEmail(pendingUser.getEmail())).thenReturn(Optional.of(pendingUser));
        doThrow(new IllegalStateException("Falha ao enviar email de ativacao"))
                .when(notifier).sendActivationCode(eq(pendingUser), any());

        AccountActivationResponse response = accountActivationService.resendActivation(pendingUser.getEmail());

        assertThat(response).isEqualTo(AccountActivationResponse.standard());
        verify(tokenRepository).save(any(ActivationToken.class));
    }
}
