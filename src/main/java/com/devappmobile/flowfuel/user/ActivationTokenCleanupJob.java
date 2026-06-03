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
 * Job de cleanup dos tokens de ativacao. Remove tokens expirados ou ja consumidos
 * ha mais de {@code retention-days}. Espelha o cleanup dos refresh / password
 * reset tokens (ADR-003).
 *
 * <p>Habilitado por padrao; desligar com
 * {@code flowfuel.account-activation.cleanup.enabled=false} (testes).
 */
@Component
@ConditionalOnProperty(
        name = "flowfuel.account-activation.cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class ActivationTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ActivationTokenCleanupJob.class);

    private final ActivationTokenRepository tokenRepository;

    @Value("${flowfuel.account-activation.cleanup.retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "${flowfuel.account-activation.cleanup.cron:0 45 3 * * *}")
    @Transactional
    public int run() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = tokenRepository.deleteOldTokens(cutoff);

        if (deleted > 0) {
            log.info("Activation token cleanup: removidos={} retentionDays={} cutoff={}",
                    deleted, retentionDays, cutoff);
        } else {
            log.debug("Activation token cleanup: nada a remover (retentionDays={}, cutoff={})",
                    retentionDays, cutoff);
        }
        return deleted;
    }
}
