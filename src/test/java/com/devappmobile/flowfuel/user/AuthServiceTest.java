package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.config.JwtUtil;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AccountActivationService accountActivationService;
    @Mock private TokenIssuer tokenIssuer;
    @Mock private com.devappmobile.flowfuel.audit.AuditLogService auditLogService;

    @InjectMocks private AuthService authService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User("test@example.com", "hashed_password", "Test User");
        existingUser.setId(1L);
    }

    // --- register ---

    @Test
    void register_comEmailNovo_criaContaPendenteEDisparaAtivacao() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setEmail("novo@example.com");
        dto.setPassword("senha123");
        dto.setName("Novo Usuario");

        when(userRepository.findByEmail("novo@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("senha123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(2L);
            return u;
        });

        UserResponseDTO response = authService.register(dto);

        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("novo@example.com");
        assertThat(response.getId()).isEqualTo(2L);
        verify(userRepository).save(argThat(u -> u.getStatus() == UserStatus.PENDING_ACTIVATION));
        verify(accountActivationService).sendActivation(argThat(u -> u.getId().equals(2L)));
        verifyNoInteractions(jwtUtil, refreshTokenService);
    }

    @Test
    void register_senhaEhHasheadaAntesDePersistar() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setEmail("hash@example.com");
        dto.setPassword("senha_plain");

        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(passwordEncoder.encode("senha_plain")).thenReturn("bcrypt_hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(3L);
            return u;
        });

        authService.register(dto);

        verify(passwordEncoder).encode("senha_plain");
        verify(userRepository).save(argThat(u -> u.getPassword().equals("bcrypt_hash")));
    }

    @Test
    void register_comEmailDuplicado_lancaConflictSemSalvar() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setEmail("test@example.com");
        dto.setPassword("senha123");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> authService.register(dto))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    // --- login ---

    @Test
    void login_comCredenciaisValidas_retornaTokenPairEGravaAuditLog() {
        TokenPairResponse expected = new TokenPairResponse("jwt-token-gerado", "refresh-plain", 900L);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha123", "hashed_password")).thenReturn(true);
        when(tokenIssuer.issueTokenPair(existingUser)).thenReturn(expected);

        TokenPairResponse response = authService.login("test@example.com", "senha123");

        assertThat(response).isEqualTo(expected);
        verify(auditLogService).record(1L, com.devappmobile.flowfuel.audit.AuditAction.LOGIN);
    }

    @Test
    void login_comEmailInexistente_lancaBadCredentials() {
        when(userRepository.findByEmail("nao@existe.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("nao@existe.com", "qualquer"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_comSenhaErrada_lancaBadCredentials() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha_errada", "hashed_password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("test@example.com", "senha_errada"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_comContaPendente_lancaAccountNotActivated() {
        existingUser.setStatus(UserStatus.PENDING_ACTIVATION);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha123", "hashed_password")).thenReturn(true);

        assertThatThrownBy(() -> authService.login("test@example.com", "senha123"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVATED));
        verifyNoInteractions(tokenIssuer);
    }

    // --- changePassword ---

    @Test
    void changePassword_comSenhaAtualCorreta_atualizaSenhaERevogaRefreshTokensEGravaAuditLog() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha_atual", "hashed_password")).thenReturn(true);
        when(passwordEncoder.matches("senha_nova", "hashed_password")).thenReturn(false);
        when(passwordEncoder.encode("senha_nova")).thenReturn("hash_nova");

        authService.changePassword(1L, "senha_atual", "senha_nova");

        verify(userRepository).save(argThat(u -> u.getPassword().equals("hash_nova")));
        verify(refreshTokenService).revokeAllForUser(1L);
        verify(auditLogService).record(1L, com.devappmobile.flowfuel.audit.AuditAction.PASSWORD_CHANGE);
    }

    @Test
    void changePassword_comSenhaAtualErrada_lancaBadCredentialsSemAlterar() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("errada", "hashed_password")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(1L, "errada", "senha_nova"))
                .isInstanceOf(BadCredentialsException.class);
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void changePassword_comNovaSenhaIgualAtual_lancaBusinessRule() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("mesma", "hashed_password")).thenReturn(true);

        assertThatThrownBy(() -> authService.changePassword(1L, "mesma", "mesma"))
                .isInstanceOf(BusinessRuleException.class);
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    // --- deleteUser ---

    @Test
    void deleteUser_existente_deletaEGravaAuditLog() {
        when(userRepository.existsById(1L)).thenReturn(true);

        authService.deleteUser(1L);

        verify(userRepository).deleteById(1L);
        verify(auditLogService).record(1L, com.devappmobile.flowfuel.audit.AuditAction.ACCOUNT_DELETION);
    }

    @Test
    void deleteUser_inexistente_lancaResourceNotFound() {
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> authService.deleteUser(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(userRepository, never()).deleteById(any());
    }
}
