package com.devappmobile.flowfuel.user;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Job de cleanup dos tokens de redefinicao de senha. Remove tokens expirados ou
 * ja consumidos ha mais de {@code retention-days}. Espelha o cleanup dos refresh
 * tokens (ADR-003).
 *
 * <p>Habilitado por padrao; desligar com
 * {@code flowfuel.password-reset.cleanup.enabled=false} (testes).
 */
@Component
@ConditionalOnProperty(
        name = "flowfuel.password-reset.cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class PasswordResetTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenCleanupJob.class);

    private final PasswordResetTokenRepository tokenRepository;

    @Value("${flowfuel.password-reset.cleanup.retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "${flowfuel.password-reset.cleanup.cron:0 30 3 * * *}")
    @Transactional
    public int run() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = tokenRepository.deleteOldTokens(cutoff);

        if (deleted > 0) {
            log.info("Password reset token cleanup: removidos={} retentionDays={} cutoff={}",
                    deleted, retentionDays, cutoff);
        } else {
            log.debug("Password reset token cleanup: nada a remover (retentionDays={}, cutoff={})",
                    retentionDays, cutoff);
        }
        return deleted;
    }
}
