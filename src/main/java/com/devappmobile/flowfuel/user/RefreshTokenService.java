package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.security.OpaqueTokenGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-token-ttl-ms:2592000000}")
    private long refreshTokenTtlMs;

    @Transactional
    public String issue(User user) {
        String plaintext = OpaqueTokenGenerator.generatePlaintext();
        RefreshToken token = new RefreshToken(user, OpaqueTokenGenerator.sha256(plaintext),
                LocalDateTime.now().plusNanos(refreshTokenTtlMs * 1_000_000L));
        refreshTokenRepository.save(token);
        return plaintext;
    }

    public record RotationResult(User user, String newRefreshToken) {}

    @Transactional
    public RotationResult rotate(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new AppException(ErrorCode.AUTH_REFRESH_INVALID, "Refresh token ausente");
        }

        RefreshToken existing = refreshTokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext))
                .orElseThrow(() -> new AppException(ErrorCode.AUTH_REFRESH_INVALID,
                        "Refresh token desconhecido"));

        if (existing.isRevoked()) {
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

        String newPlain = OpaqueTokenGenerator.generatePlaintext();
        RefreshToken replacement = new RefreshToken(existing.getUser(),
                OpaqueTokenGenerator.sha256(newPlain),
                LocalDateTime.now().plusNanos(refreshTokenTtlMs * 1_000_000L));
        refreshTokenRepository.save(replacement);

        existing.setRevokedAt(LocalDateTime.now());
        existing.setReplacedBy(replacement);

        return new RotationResult(existing.getUser(), newPlain);
    }

    @Transactional
    public void revoke(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext)).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.setRevokedAt(LocalDateTime.now());
            }
        });
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllActiveByUserId(userId, LocalDateTime.now());
    }
}
