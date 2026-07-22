package com.devappmobile.flowfuel.user;

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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private StorageService storageService;

    @InjectMocks private UserProfileService userProfileService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User("test@example.com", "hashed_password", "Test User");
        existingUser.setId(1L);
    }

    // --- getUserProfile ---

    @Test
    void getUserProfile_usuarioExistente_retornaDto() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));

        UserResponseDTO response = userProfileService.getUserProfile(1L);

        assertThat(response.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void getUserProfile_usuarioInexistente_lancaResourceNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userProfileService.getUserProfile(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getUserProfile_retornaProfilePicturePath() {
        existingUser.setProfilePicture("profile_pictures/1_foto.jpg");
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));

        UserResponseDTO response = userProfileService.getUserProfile(1L);

        assertThat(response.getProfilePicture()).isEqualTo("/auth/1/profile-picture");
    }

    // --- uploadProfilePictureResponse ---

    @Test
    void upload_comTipoInvalido_lancaBusinessRule() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.gif", "image/gif", new byte[100]);

        assertThatThrownBy(() -> userProfileService.uploadProfilePictureResponse(1L, file))
                .isInstanceOf(BusinessRuleException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void upload_comArquivoMaiorQue5MB_lancaBusinessRule() {
        byte[] bigFile = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", bigFile);

        assertThatThrownBy(() -> userProfileService.uploadProfilePictureResponse(1L, file))
                .isInstanceOf(BusinessRuleException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void upload_comImagemValida_atualizaPath() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any())).thenReturn(existingUser);

        UploadResponse response = userProfileService.uploadProfilePictureResponse(1L, file);

        assertThat(response).isNotNull();
        assertThat(existingUser.getProfilePicture()).isEqualTo("profile_pictures/1_foto.jpg");
    }

    @Test
    void uploadProfilePictureResponse_comImagemValida_retornaInternalUrl() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any())).thenReturn(existingUser);

        UploadResponse response = userProfileService.uploadProfilePictureResponse(1L, file);

        assertThat(response).isNotNull();
        assertThat(response.getInternalUrl()).isEqualTo("/auth/1/profile-picture");
        assertThat(existingUser.getProfilePicture()).isEqualTo("profile_pictures/1_foto.jpg");
    }

    // --- updateUserProfile ---

    @Test
    void updateUserProfile_comNameEPhone_atualizaSemTocarEmail() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserUpdateDTO dto = new UserUpdateDTO(null, "Novo Nome", "11999990000");
        UserResponseDTO result = userProfileService.updateUserProfile(1L, dto);

        assertThat(result.getName()).isEqualTo("Novo Nome");
        assertThat(result.getEmail()).isEqualTo("test@example.com");
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void updateUserProfile_comEmailNovo_verificaDuplicidadeEAtualiza() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.findByEmail("novo@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserUpdateDTO dto = new UserUpdateDTO("novo@example.com", null, null);
        UserResponseDTO result = userProfileService.updateUserProfile(1L, dto);

        assertThat(result.getEmail()).isEqualTo("novo@example.com");
        verify(userRepository).findByEmail("novo@example.com");
    }

    @Test
    void updateUserProfile_comEmailDuplicado_lancaConflict() {
        User outro = new User("outro@example.com", "hash", "Outro");
        outro.setId(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.findByEmail("outro@example.com")).thenReturn(Optional.of(outro));

        UserUpdateDTO dto = new UserUpdateDTO("outro@example.com", null, null);

        assertThatThrownBy(() -> userProfileService.updateUserProfile(1L, dto))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUserProfile_comTodosCamposNulos_naoAlteraNada() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserUpdateDTO dto = new UserUpdateDTO(null, null, null);
        UserResponseDTO result = userProfileService.updateUserProfile(1L, dto);

        assertThat(result.getEmail()).isEqualTo("test@example.com");
        assertThat(result.getName()).isEqualTo("Test User");
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void updateUserProfile_usuarioInexistente_lancaResourceNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        UserUpdateDTO dto = new UserUpdateDTO(null, "Nome", null);

        assertThatThrownBy(() -> userProfileService.updateUserProfile(99L, dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- getProfilePictureUrl ---

    @Test
    void getProfilePictureUrl_comFoto_retornaUrlPublica() {
        existingUser.setProfilePicture("profile_pictures/1_foto.jpg");
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(storageService.publicUrl("profile_pictures/1_foto.jpg"))
                .thenReturn("https://pub-test.r2.dev/profile_pictures/1_foto.jpg");

        String url = userProfileService.getProfilePictureUrl(1L);

        assertThat(url).isEqualTo("https://pub-test.r2.dev/profile_pictures/1_foto.jpg");
    }

    @Test
    void getProfilePictureUrl_semFoto_retornaNull() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));

        String url = userProfileService.getProfilePictureUrl(1L);

        assertThat(url).isNull();
        verify(storageService, never()).publicUrl(any());
    }
}
