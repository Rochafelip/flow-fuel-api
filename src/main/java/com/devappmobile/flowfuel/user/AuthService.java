package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.config.JwtUtil;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AccountActivationService accountActivationService;

    /**
     * Cadastra um novo usuario com status {@link UserStatus#PENDING_ACTIVATION} e
     * dispara o envio do link de ativacao por email. NAO loga o usuario: o login
     * so passa a funcionar apos a confirmacao do email (ver {@link #login}).
     */
    public UserResponseDTO register(UserRegisterDTO dto) {
        if (userRepository.findByEmail(dto.getEmail()).isPresent()) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email já cadastrado");
        }

        User user = new User();
        user.setEmail(dto.getEmail());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setName(dto.getName());
        user.setPhone(dto.getPhone());
        user.setStatus(UserStatus.PENDING_ACTIVATION);

        User saved = userRepository.save(user);
        accountActivationService.sendActivation(saved);
        return UserResponseDTO.from(saved);
    }

    public TokenPairResponse login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .filter(u -> passwordEncoder.matches(password, u.getPassword()))
                .orElseThrow(() -> new BadCredentialsException("Email ou senha inválidos"));

        if (!user.isActive()) {
            throw new AppException(ErrorCode.ACCOUNT_NOT_ACTIVATED,
                    "Conta não ativada. Verifique seu email para ativar a conta.");
        }

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
    @Transactional
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

    public void deleteUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("Usuário", userId);
        }
        userRepository.deleteById(userId);
    }

    private TokenPairResponse issueTokenPair(User user) {
        String accessToken = jwtUtil.generateToken(user.getEmail(), user.getId());
        String refreshToken = refreshTokenService.issue(user);
        return new TokenPairResponse(accessToken, refreshToken,
                jwtUtil.getAccessTokenTtlMs() / 1000);
    }

    private User findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário", userId));
    }
}
