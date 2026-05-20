package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.config.JwtUtil;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp");

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public ResponseEntity<UserResponseDTO> register(UserRegisterDTO dto) {
        if (userRepository.findByEmail(dto.getEmail()).isPresent()) {
            return ResponseEntity.badRequest().build();
        }

        User user = new User();
        user.setEmail(dto.getEmail());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setName(dto.getName());
        user.setPhone(dto.getPhone());

        return ResponseEntity.ok(UserResponseDTO.from(userRepository.save(user)));
    }

    public ResponseEntity<?> login(String email, String password) {
        Optional<User> user = userRepository.findByEmail(email);

        if (user.isEmpty() || !passwordEncoder.matches(password, user.get().getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Email ou senha inválidos");
        }

        User foundUser = user.get();
        String token = jwtUtil.generateToken(foundUser.getEmail(), foundUser.getId());

        return ResponseEntity.ok(new LoginResponse(token));
    }

    public ResponseEntity<UserResponseDTO> getUserProfile(Long userId) {
        return userRepository.findById(userId)
                .map(user -> ResponseEntity.ok(UserResponseDTO.from(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    public ResponseEntity<UserResponseDTO> updateUserProfile(Long userId, UserRegisterDTO dto) {
        Optional<User> userOptional = userRepository.findById(userId);
        if (userOptional.isEmpty()) return ResponseEntity.notFound().build();

        User user = userOptional.get();

        if (dto.getName() != null) user.setName(dto.getName());
        if (dto.getPhone() != null) user.setPhone(dto.getPhone());

        if (dto.getEmail() != null && !dto.getEmail().equals(user.getEmail())) {
            if (userRepository.findByEmail(dto.getEmail()).isPresent()) {
                return ResponseEntity.badRequest().build();
            }
            user.setEmail(dto.getEmail());
        }

        return ResponseEntity.ok(UserResponseDTO.from(userRepository.save(user)));
    }

    public ResponseEntity<?> uploadProfilePicture(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("Arquivo não informado");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            return ResponseEntity.badRequest().body("Tipo de arquivo inválido. Permitido: JPEG, PNG, WEBP");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body("Arquivo excede o tamanho máximo de 5 MB");
        }

        Optional<User> userOptional = userRepository.findById(userId);
        if (userOptional.isEmpty()) return ResponseEntity.notFound().build();

        User user = userOptional.get();
        // Sanitiza o nome do arquivo original antes de compor o path
        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "photo";
        user.setProfilePicture("profile_pictures/" + userId + "_" + originalName);

        userRepository.save(user);
        return ResponseEntity.ok("Foto atualizada com sucesso");
    }

    public ResponseEntity<?> deleteUser(Long userId) {
        if (userRepository.existsById(userId)) {
            userRepository.deleteById(userId);
            return ResponseEntity.ok("Conta excluída com sucesso");
        }
        return ResponseEntity.notFound().build();
    }
}