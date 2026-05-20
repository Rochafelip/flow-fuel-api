package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.config.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private UserService userService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User("test@example.com", "hashed_password", "Test User");
        existingUser.setId(1L);
    }

    // --- register ---

    @Test
    void register_comEmailNovo_retorna200EDto() {
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

        ResponseEntity<UserResponseDTO> response = userService.register(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getEmail()).isEqualTo("novo@example.com");
        assertThat(response.getBody().getId()).isEqualTo(2L);
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
    void register_comEmailDuplicado_retorna400SemSalvar() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setEmail("test@example.com");
        dto.setPassword("senha123");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));

        ResponseEntity<UserResponseDTO> response = userService.register(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).save(any());
    }

    // --- login ---

    @Test
    void login_comCredenciaisValidas_retornaToken() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha123", "hashed_password")).thenReturn(true);
        when(jwtUtil.generateToken("test@example.com", 1L)).thenReturn("jwt-token-gerado");

        ResponseEntity<?> response = userService.login("test@example.com", "senha123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        LoginResponse body = (LoginResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getToken()).isEqualTo("jwt-token-gerado");
    }

    @Test
    void login_comEmailInexistente_retorna401() {
        when(userRepository.findByEmail("nao@existe.com")).thenReturn(Optional.empty());

        ResponseEntity<?> response = userService.login("nao@existe.com", "qualquer");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_comSenhaErrada_retorna401() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha_errada", "hashed_password")).thenReturn(false);

        ResponseEntity<?> response = userService.login("test@example.com", "senha_errada");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- getUserProfile ---

    @Test
    void getUserProfile_usuarioExistente_retornaDto() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));

        ResponseEntity<UserResponseDTO> response = userService.getUserProfile(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void getUserProfile_usuarioInexistente_retorna404() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<UserResponseDTO> response = userService.getUserProfile(99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- uploadProfilePicture ---

    @Test
    void upload_comTipoInvalido_retorna400() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.gif", "image/gif", new byte[100]);

        ResponseEntity<?> response = userService.uploadProfilePicture(1L, file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).save(any());
    }

    @Test
    void upload_comArquivoMaiorQue5MB_retorna400() {
        byte[] bigFile = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", bigFile);

        ResponseEntity<?> response = userService.uploadProfilePicture(1L, file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).save(any());
    }

    @Test
    void upload_comImagemValida_atualizaPathERetorna200() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any())).thenReturn(existingUser);

        ResponseEntity<?> response = userService.uploadProfilePicture(1L, file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(existingUser.getProfilePicture()).isEqualTo("profile_pictures/1_foto.jpg");
    }

    // --- deleteUser ---

    @Test
    void deleteUser_existente_retorna200EDeleta() {
        when(userRepository.existsById(1L)).thenReturn(true);

        ResponseEntity<?> response = userService.deleteUser(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(userRepository).deleteById(1L);
    }

    @Test
    void deleteUser_inexistente_retorna404() {
        when(userRepository.existsById(99L)).thenReturn(false);

        ResponseEntity<?> response = userService.deleteUser(99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(userRepository, never()).deleteById(any());
    }
}
