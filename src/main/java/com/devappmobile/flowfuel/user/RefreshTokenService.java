package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Emissao, rotacao e revogacao de refresh tokens (ADR-003).
 *
 * <p>O token e opaco (32 bytes aleatorios em base64-url). No banco gravamos
 * apenas SHA-256 do plaintext — o token completo so existe em memoria
 * na resposta HTTP.
 *
 * <p>Rotacao: a cada {@link #rotate(String)} emitimos um novo token e marcamos
 * o antigo como revogado, encadeando via {@code replaced_by_id}. Se um token
 * ja revogado for apresentado novamente, tratamos como sinal de comprometimento
 * e revogamos toda a cadeia ativa do usuario.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-token-ttl-ms:2592000000}")
    private long refreshTokenTtlMs;

    @Transactional
    public String issue(User user) {
        String plaintext = generatePlaintext();
        RefreshToken token = new RefreshToken(user, sha256(plaintext),
                LocalDateTime.now().plusNanos(refreshTokenTtlMs * 1_000_000L));
        refreshTokenRepository.save(token);
        return plaintext;
    }

    /**
     * Resultado da rotacao: o usuario dono do token e o novo plaintext.
     */
    public record RotationResult(User user, String newRefreshToken) {}

    @Transactional
    public RotationResult rotate(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new AppException(ErrorCode.AUTH_REFRESH_INVALID, "Refresh token ausente");
        }

        RefreshToken existing = refreshTokenRepository.findByTokenHash(sha256(plaintext))
                .orElseThrow(() -> new AppException(ErrorCode.AUTH_REFRESH_INVALID,
                        "Refresh token desconhecido"));

        if (existing.isRevoked()) {
            // Re-uso de token ja rotacionado: sinal de comprometimento.
            // Revogar toda a cadeia ativa do usuario.
            log.warn("Re-uso de refresh token detectado, revogando todas as sessoes userId={}",
                    existing.getUser().getId());
            refreshTokenRepository.revokeAllActiveByUserId(existing.getUser().getId(),
                    LocalDateTime.now());
            throw new AppException(ErrorCode.AUTH_REFRESH_REVOKED,
                    "Refresh token revogado — sessoes invalidadas por seguranca");
        }

        if (existing.isExpired()) {
            throw new AppException(ErrorCode.AUTH_REFRESH_EXPIRED, "Refresh token expirado");
        }

        String newPlain = generatePlaintext();
        RefreshToken replacement = new RefreshToken(existing.getUser(), sha256(newPlain),
                LocalDateTime.now().plusNanos(refreshTokenTtlMs * 1_000_000L));
        refreshTokenRepository.save(replacement);

        existing.setRevokedAt(LocalDateTime.now());
        existing.setReplacedBy(replacement);
        // existing ja esta managed; flush implicito ao commit da transacao.

        return new RotationResult(existing.getUser(), newPlain);
    }

    @Transactional
    public void revoke(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(sha256(plaintext)).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.setRevokedAt(LocalDateTime.now());
            }
        });
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllActiveByUserId(userId, LocalDateTime.now());
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
            // SHA-256 e parte do JRE; nunca deve faltar.
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }
}
