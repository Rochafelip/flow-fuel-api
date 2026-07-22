package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import com.devappmobile.flowfuel.storage.StorageService;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp");

    private final UserRepository userRepository;
    private final StorageService storageService;

    public UserResponseDTO getUserProfile(Long userId) {
        User user = findUserOrThrow(userId);
        String profileKey = user.getProfilePicture();
        String internalUrl = profileKey != null ? ("/auth/" + userId + "/profile-picture") : null;

        UserResponseDTO dto = UserResponseDTO.from(user);
        dto.setProfilePicture(internalUrl);
        return dto;
    }

    public String getProfilePictureUrl(Long userId) {
        String key = findUserOrThrow(userId).getProfilePicture();
        return key != null ? storageService.publicUrl(key) : null;
    }

    public void removeProfilePicture(Long userId) {
        User user = findUserOrThrow(userId);
        String key = user.getProfilePicture();
        if (key != null) {
            storageService.delete(key);
            user.setProfilePicture(null);
            userRepository.save(user);
        }
    }

    public UserResponseDTO updateUserProfile(Long userId, UserUpdateDTO dto) {
        User user = findUserOrThrow(userId);

        if (dto.name() != null) user.setName(dto.name());
        if (dto.phone() != null) user.setPhone(dto.phone());

        if (dto.email() != null && !dto.email().equals(user.getEmail())) {
            if (userRepository.findByEmail(dto.email()).isPresent()) {
                throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email já cadastrado");
            }
            user.setEmail(dto.email());
        }

        return UserResponseDTO.from(userRepository.save(user));
    }

    public UploadResponse uploadProfilePictureResponse(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Arquivo não informado");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new BusinessRuleException("Tipo de arquivo inválido. Permitido: JPEG, PNG, WEBP");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessRuleException("Arquivo excede o tamanho máximo de 5 MB");
        }

        User user = findUserOrThrow(userId);

        // cleanup previous image if present
        String previousKey = user.getProfilePicture();
        if (previousKey != null) {
            try {
                storageService.delete(previousKey);
            } catch (Exception ignored) {
            }
        }

        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "photo";

        String key = "profile_pictures/" + userId + "_" + originalName;
        storageService.upload(file, key);
        user.setProfilePicture(key);

        userRepository.save(user);

        String internalUrl = "/auth/" + userId + "/profile-picture";
        return new UploadResponse(internalUrl);
    }

    private User findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário", userId));
    }
}
