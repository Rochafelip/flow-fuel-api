package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private PasswordResetNotifier notifier;

    @InjectMocks private PasswordResetService service;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User("test@example.com", "hashed_password", "Test User");
        existingUser.setId(1L);
        ReflectionTestUtils.setField(service, "tokenTtlMinutes", 30L);
        ReflectionTestUtils.setField(service, "exposeToken", false);
    }

    // --- requestReset ---

    @Test
    void requestReset_emailExistente_geraTokenInvalidaAnterioresEEntrega() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));

        ForgotPasswordResponse response = service.requestReset("test@example.com");

        verify(tokenRepository).invalidateActiveByUserId(eq(1L), any(LocalDateTime.class));
        verify(tokenRepository).save(any(PasswordResetToken.class));
        verify(notifier).sendResetToken(eq(existingUser), anyString());
        // expose-token desligado: nao vaza o token na resposta
        assertThat(response.resetToken()).isNull();
        assertThat(response.message()).isNotBlank();
    }

    @Test
    void requestReset_persisteHashNaoOPlaintext() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));

        service.requestReset("test@example.com");

        ArgumentCaptor<String> plaintextCaptor = ArgumentCaptor.forClass(String.class);
        verify(notifier).sendResetToken(eq(existingUser), plaintextCaptor.capture());
        String plaintext = plaintextCaptor.getValue();

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        String persistedHash = tokenCaptor.getValue().getTokenHash();

        assertThat(persistedHash).isNotEqualTo(plaintext);
        assertThat(persistedHash).hasSize(64); // SHA-256 em hex
    }

    @Test
    void requestReset_comExposeTokenLigado_devolveTokenNaResposta() {
        ReflectionTestUtils.setField(service, "exposeToken", true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));

        ForgotPasswordResponse response = service.requestReset("test@example.com");

        assertThat(response.resetToken()).isNotBlank();
    }

    @Test
    void requestReset_emailInexistente_naoVazaEMantemRespostaPadrao() {
        when(userRepository.findByEmail("nao@existe.com")).thenReturn(Optional.empty());

        ForgotPasswordResponse response = service.requestReset("nao@existe.com");

        // resposta identica ao caso de sucesso (anti-enumeracao), sem persistir nada
        assertThat(response.message()).isEqualTo(ForgotPasswordResponse.standard().message());
        assertThat(response.resetToken()).isNull();
        verify(tokenRepository, never()).save(any());
        verify(notifier, never()).sendResetToken(any(), anyString());
    }

    // --- reset ---

    @Test
    void reset_comTokenValido_trocaSenhaConsomeTokenERevogaSessoes() {
        // gera um token real para conhecer o plaintext correspondente ao hash
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        service.requestReset("test@example.com");
        ArgumentCaptor<String> plaintextCaptor = ArgumentCaptor.forClass(String.class);
        verify(notifier).sendResetToken(eq(existingUser), plaintextCaptor.capture());
        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        PasswordResetToken stored = tokenCaptor.getValue();
        String plaintext = plaintextCaptor.getValue();

        when(tokenRepository.findByTokenHash(stored.getTokenHash())).thenReturn(Optional.of(stored));
        when(passwordEncoder.encode("nova_senha")).thenReturn("hash_nova");

        service.reset(plaintext, "nova_senha");

        verify(userRepository).save(argThat(u -> u.getPassword().equals("hash_nova")));
        verify(refreshTokenService).revokeAllForUser(1L);
        assertThat(stored.isUsed()).isTrue();
    }

    @Test
    void reset_comTokenDesconhecido_lancaResetInvalido() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reset("token-qualquer", "nova_senha"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_RESET_INVALID);
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void reset_comTokenExpirado_lancaResetInvalido() {
        PasswordResetToken expired = new PasswordResetToken(existingUser, "hash",
                LocalDateTime.now().minusMinutes(1));
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.reset("token", "nova_senha"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_RESET_INVALID);
        verify(userRepository, never()).save(any());
    }

    @Test
    void reset_comTokenJaUsado_lancaResetInvalido() {
        PasswordResetToken used = new PasswordResetToken(existingUser, "hash",
                LocalDateTime.now().plusMinutes(30));
        used.setUsedAt(LocalDateTime.now().minusMinutes(1));
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.reset("token", "nova_senha"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_RESET_INVALID);
        verify(userRepository, never()).save(any());
    }

    @Test
    void reset_comTokenEmBranco_lancaResetInvalido() {
        assertThatThrownBy(() -> service.reset("  ", "nova_senha"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_RESET_INVALID);
        verifyNoInteractions(userRepository);
    }
}
