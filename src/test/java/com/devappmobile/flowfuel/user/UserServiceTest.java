package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.config.JwtUtil;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private StorageService storageService;

    @InjectMocks private UserService userService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User("test@example.com", "hashed_password", "Test User");
        existingUser.setId(1L);
    }

    // --- register ---

    @Test
    void register_comEmailNovo_retornaDto() {
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

        UserResponseDTO response = userService.register(dto);

        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("novo@example.com");
        assertThat(response.getId()).isEqualTo(2L);
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

        userService.register(dto);

        verify(passwordEncoder).encode("senha_plain");
        verify(userRepository).save(argThat(u -> u.getPassword().equals("bcrypt_hash")));
    }

    @Test
    void register_comEmailDuplicado_lancaConflictSemSalvar() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setEmail("test@example.com");
        dto.setPassword("senha123");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> userService.register(dto))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    // --- login ---

    @Test
    void login_comCredenciaisValidas_retornaTokenPair() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha123", "hashed_password")).thenReturn(true);
        when(jwtUtil.generateToken("test@example.com", 1L)).thenReturn("jwt-token-gerado");
        when(jwtUtil.getAccessTokenTtlMs()).thenReturn(900_000L);
        when(refreshTokenService.issue(existingUser)).thenReturn("refresh-plain");

        TokenPairResponse response = userService.login("test@example.com", "senha123");

        assertThat(response).isNotNull();
        assertThat(response.accessToken()).isEqualTo("jwt-token-gerado");
        assertThat(response.refreshToken()).isEqualTo("refresh-plain");
        assertThat(response.expiresIn()).isEqualTo(900L);
    }

    @Test
    void login_comEmailInexistente_lancaBadCredentials() {
        when(userRepository.findByEmail("nao@existe.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login("nao@existe.com", "qualquer"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_comSenhaErrada_lancaBadCredentials() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha_errada", "hashed_password")).thenReturn(false);

        assertThatThrownBy(() -> userService.login("test@example.com", "senha_errada"))
                .isInstanceOf(BadCredentialsException.class);
    }

    // --- getUserProfile ---

    @Test
    void getUserProfile_usuarioExistente_retornaDto() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));

        UserResponseDTO response = userService.getUserProfile(1L);

        assertThat(response.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void getUserProfile_usuarioInexistente_lancaResourceNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserProfile(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- uploadProfilePicture ---

    @Test
    void upload_comTipoInvalido_lancaBusinessRule() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.gif", "image/gif", new byte[100]);

        assertThatThrownBy(() -> userService.uploadProfilePicture(1L, file))
                .isInstanceOf(BusinessRuleException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void upload_comArquivoMaiorQue5MB_lancaBusinessRule() {
        byte[] bigFile = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", bigFile);

        assertThatThrownBy(() -> userService.uploadProfilePicture(1L, file))
                .isInstanceOf(BusinessRuleException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void upload_comImagemValida_atualizaPath() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any())).thenReturn(existingUser);

        String response = userService.uploadProfilePicture(1L, file);

        assertThat(response).isEqualTo("Foto atualizada com sucesso");
        assertThat(existingUser.getProfilePicture()).isEqualTo("profile_pictures/1_foto.jpg");
    }

    @Test
    void uploadProfilePictureResponse_comImagemValida_retornaUrls() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any())).thenReturn(existingUser);
        when(storageService.getUrl("profile_pictures/1_foto.jpg")).thenReturn("https://signed-url.example.com/profile_pictures/1_foto.jpg");

        UploadResponse response = userService.uploadProfilePictureResponse(1L, file);

        assertThat(response).isNotNull();
        assertThat(response.getInternalUrl()).isEqualTo("/auth/1/profile-picture");
        assertThat(response.getSignedUrl()).isEqualTo("https://signed-url.example.com/profile_pictures/1_foto.jpg");
        assertThat(existingUser.getProfilePicture()).isEqualTo("profile_pictures/1_foto.jpg");
    }

    @Test
    void getUserProfile_retornaProfilePictureUrl() {
        existingUser.setProfilePicture("profile_pictures/1_foto.jpg");
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(storageService.getUrl("profile_pictures/1_foto.jpg")).thenReturn("https://signed-url.example.com/profile_pictures/1_foto.jpg");

        UserResponseDTO response = userService.getUserProfile(1L);

        assertThat(response.getProfilePicture()).isEqualTo("/auth/1/profile-picture");
        assertThat(response.getProfilePictureUrl()).isEqualTo("https://signed-url.example.com/profile_pictures/1_foto.jpg");
    }

    // --- changePassword ---

    @Test
    void changePassword_comSenhaAtualCorreta_atualizaSenhaERevogaRefreshTokens() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha_atual", "hashed_password")).thenReturn(true);
        when(passwordEncoder.matches("senha_nova", "hashed_password")).thenReturn(false);
        when(passwordEncoder.encode("senha_nova")).thenReturn("hash_nova");

        userService.changePassword(1L, "senha_atual", "senha_nova");

        verify(userRepository).save(argThat(u -> u.getPassword().equals("hash_nova")));
        verify(refreshTokenService).revokeAllForUser(1L);
    }

    @Test
    void changePassword_comSenhaAtualErrada_lancaBadCredentialsSemAlterar() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("errada", "hashed_password")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(1L, "errada", "senha_nova"))
                .isInstanceOf(BadCredentialsException.class);
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void changePassword_comNovaSenhaIgualAtual_lancaBusinessRule() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("mesma", "hashed_password")).thenReturn(true);

        assertThatThrownBy(() -> userService.changePassword(1L, "mesma", "mesma"))
                .isInstanceOf(BusinessRuleException.class);
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    // --- deleteUser ---

    @Test
    void deleteUser_existente_deleta() {
        when(userRepository.existsById(1L)).thenReturn(true);

        userService.deleteUser(1L);

        verify(userRepository).deleteById(1L);
    }

    @Test
    void deleteUser_inexistente_lancaResourceNotFound() {
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteUser(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(userRepository, never()).deleteById(any());
    }
}
