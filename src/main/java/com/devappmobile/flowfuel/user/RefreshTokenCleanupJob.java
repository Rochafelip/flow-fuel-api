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
 * Job de cleanup de refresh tokens (ADR-003).
 *
 * <p>Remove tokens cuja janela de utilidade ja passou:
 * <ul>
 *   <li>Tokens expirados ha mais de {@code retention-days}.</li>
 *   <li>Tokens revogados ha mais de {@code retention-days} — manter por um
 *       periodo permite que a deteccao de re-uso continue funcionando.</li>
 * </ul>
 *
 * <p>Habilitado por padrao; desligar com
 * {@code flowfuel.refresh-token.cleanup.enabled=false} (testes).
 */
@Component
@ConditionalOnProperty(
        name = "flowfuel.refresh-token.cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${flowfuel.refresh-token.cleanup.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "${flowfuel.refresh-token.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public int run() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        int unlinked = refreshTokenRepository.clearReplacedByOnOldTokens(cutoff);
        int deleted = refreshTokenRepository.deleteOldTokens(cutoff);

        if (deleted > 0 || unlinked > 0) {
            log.info("Refresh token cleanup: removidos={} unlinkedReplacedBy={} retentionDays={} cutoff={}",
                    deleted, unlinked, retentionDays, cutoff);
        } else {
            log.debug("Refresh token cleanup: nada a remover (retentionDays={}, cutoff={})",
                    retentionDays, cutoff);
        }
        return deleted;
    }
}
