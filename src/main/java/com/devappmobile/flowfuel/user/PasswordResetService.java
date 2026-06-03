package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Fluxo "esqueci minha senha" (request + reset).
 *
 * <p>Segue o padrao dos refresh tokens (ADR-003): o token e opaco (32 bytes
 * aleatorios em base64-url) e no banco gravamos apenas seu SHA-256. O token e
 * de uso unico e curta duracao.
 *
 * <p>Anti-enumeracao: {@link #requestReset(String)} responde sempre da mesma
 * forma, exista ou nao o email. Ao efetivar a troca ({@link #reset(String, String)})
 * todas as sessoes ativas (refresh tokens) sao revogadas.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetNotifier notifier;

    @Value("${flowfuel.password-reset.token-ttl-minutes:30}")
    private long tokenTtlMinutes;

    @Value("${flowfuel.password-reset.expose-token:false}")
    private boolean exposeToken;

    /**
     * Inicia o fluxo de redefinicao para um email. Resposta identica exista ou
     * nao o email (anti-enumeracao). Cada solicitacao invalida tokens anteriores.
     */
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

        String plaintext = generatePlaintext();
        tokenRepository.save(new PasswordResetToken(user, sha256(plaintext),
                now.plusMinutes(tokenTtlMinutes)));

        notifier.sendResetToken(user, plaintext);

        return exposeToken ? response.withToken(plaintext) : response;
    }

    /**
     * Efetiva a troca de senha a partir de um token valido. Apos sucesso o token
     * e consumido (uso unico) e todas as sessoes ativas do usuario sao revogadas.
     */
    @Transactional
    public void reset(String plaintext, String newPassword) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new AppException(ErrorCode.AUTH_RESET_INVALID, "Token de redefinição ausente");
        }

        PasswordResetToken token = tokenRepository.findByTokenHash(sha256(plaintext))
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

    private static String generatePlaintext() {
        byte[] buf = new byte[TOKEN_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }
}
