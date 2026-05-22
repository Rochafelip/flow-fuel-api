package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.config.JwtUtil;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp");

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    public UserResponseDTO register(UserRegisterDTO dto) {
        if (userRepository.findByEmail(dto.getEmail()).isPresent()) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email já cadastrado");
        }

        User user = new User();
        user.setEmail(dto.getEmail());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setName(dto.getName());
        user.setPhone(dto.getPhone());

        return UserResponseDTO.from(userRepository.save(user));
    }

    public TokenPairResponse login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .filter(u -> passwordEncoder.matches(password, u.getPassword()))
                .orElseThrow(() -> new BadCredentialsException("Email ou senha inválidos"));

        return issueTokenPair(user);
    }

    public TokenPairResponse refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotated = refreshTokenService.rotate(refreshToken);
        String accessToken = jwtUtil.generateToken(rotated.user().getEmail(), rotated.user().getId());
        return new TokenPairResponse(accessToken, rotated.newRefreshToken(),
                jwtUtil.getAccessTokenTtlMs() / 1000);
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    /**
     * Troca a senha do usuario. Apos sucesso, revoga todas as sessoes ativas
     * (refresh tokens) — o usuario precisa logar novamente em todos os dispositivos.
     */
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = findUserOrThrow(userId);

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new BadCredentialsException("Senha atual inválida");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BusinessRuleException("Nova senha deve ser diferente da atual");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(userId);
    }

    private TokenPairResponse issueTokenPair(User user) {
        String accessToken = jwtUtil.generateToken(user.getEmail(), user.getId());
        String refreshToken = refreshTokenService.issue(user);
        return new TokenPairResponse(accessToken, refreshToken,
                jwtUtil.getAccessTokenTtlMs() / 1000);
    }

    public UserResponseDTO getUserProfile(Long userId) {
        return UserResponseDTO.from(findUserOrThrow(userId));
    }

    public UserResponseDTO updateUserProfile(Long userId, UserRegisterDTO dto) {
        User user = findUserOrThrow(userId);

        if (dto.getName() != null) user.setName(dto.getName());
        if (dto.getPhone() != null) user.setPhone(dto.getPhone());

        if (dto.getEmail() != null && !dto.getEmail().equals(user.getEmail())) {
            if (userRepository.findByEmail(dto.getEmail()).isPresent()) {
                throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email já cadastrado");
            }
            user.setEmail(dto.getEmail());
        }

        return UserResponseDTO.from(userRepository.save(user));
    }

    public String uploadProfilePicture(Long userId, MultipartFile file) {
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

        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "photo";
        user.setProfilePicture("profile_pictures/" + userId + "_" + originalName);

        userRepository.save(user);
        return "Foto atualizada com sucesso";
    }

    public void deleteUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("Usuário", userId);
        }
        userRepository.deleteById(userId);
    }

    private User findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário", userId));
    }
}
