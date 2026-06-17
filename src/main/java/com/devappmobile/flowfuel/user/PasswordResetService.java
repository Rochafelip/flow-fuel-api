package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.security.OpaqueTokenGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetNotifier notifier;

    @Value("${flowfuel.password-reset.token-ttl-minutes:30}")
    private long tokenTtlMinutes;

    @Value("${flowfuel.password-reset.expose-token:false}")
    private boolean exposeToken;

    @Transactional
    public ForgotPasswordResponse requestReset(String email) {
        ForgotPasswordResponse response = ForgotPasswordResponse.standard();

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.info("Solicitacao de reset para email nao cadastrado — ignorando silenciosamente");
            return response;
        }

        User user = userOpt.get();
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.invalidateActiveByUserId(user.getId(), now);

        String plaintext = OpaqueTokenGenerator.generatePlaintext();
        tokenRepository.save(new PasswordResetToken(user, OpaqueTokenGenerator.sha256(plaintext),
                now.plusMinutes(tokenTtlMinutes)));

        notifier.sendResetToken(user, plaintext);

        return exposeToken ? response.withToken(plaintext) : response;
    }

    @Transactional
    public void reset(String plaintext, String newPassword) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new AppException(ErrorCode.AUTH_RESET_INVALID, "Token de redefinição ausente");
        }

        PasswordResetToken token = tokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext))
                .filter(PasswordResetToken::isUsable)
                .orElseThrow(() -> new AppException(ErrorCode.AUTH_RESET_INVALID,
                        "Token de redefinição inválido ou expirado"));

        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsedAt(LocalDateTime.now());
        refreshTokenService.revokeAllForUser(user.getId());

        log.info("Senha redefinida via token de reset userId={}", user.getId());
    }
}
