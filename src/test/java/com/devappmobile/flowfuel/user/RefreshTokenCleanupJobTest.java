package com.devappmobile.flowfuel.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "flowfuel.refresh-token.cleanup.enabled=true")
@Import({RefreshTokenService.class, RefreshTokenCleanupJob.class})
class RefreshTokenCleanupJobTest {

    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenCleanupJob cleanupJob;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refreshTokenService, "refreshTokenTtlMs", 60_000L);
        ReflectionTestUtils.setField(cleanupJob, "retentionDays", 30);
        user = userRepository.save(new User("cleanup@test.com", "hash", "Cleanup"));
    }

    @Test
    void run_naoDeletaTokensAtivos() {
        refreshTokenService.issue(user);

        int deleted = cleanupJob.run();

        assertThat(deleted).isZero();
        assertThat(refreshTokenRepository.findAll()).hasSize(1);
    }

    @Test
    void run_deletaTokensExpiradosHaMaisDeRetencao() {
        refreshTokenService.issue(user);
        refreshTokenRepository.findAll().forEach(rt -> {
            // Expirou ha 31 dias.
            rt.setExpiresAt(LocalDateTime.now().minusDays(31));
            refreshTokenRepository.save(rt);
        });

        int deleted = cleanupJob.run();

        assertThat(deleted).isOne();
        assertThat(refreshTokenRepository.findAll()).isEmpty();
    }

    @Test
    void run_naoDeletaExpiradosDentroDaJanelaDeRetencao() {
        refreshTokenService.issue(user);
        refreshTokenRepository.findAll().forEach(rt -> {
            // Expirou ha 10 dias, ainda dentro da janela de 30.
            rt.setExpiresAt(LocalDateTime.now().minusDays(10));
            refreshTokenRepository.save(rt);
        });

        int deleted = cleanupJob.run();

        assertThat(deleted).isZero();
        assertThat(refreshTokenRepository.findAll()).hasSize(1);
    }

    @Test
    void run_deletaCadeiaInteiraDeRevogadosAntigosSemViolarFK() {
        // Monta cadeia T1 -> T2 -> T3 (T3 ativo).
        String t1 = refreshTokenService.issue(user);
        String t2 = refreshTokenService.rotate(t1).newRefreshToken();
        refreshTokenService.rotate(t2);
        assertThat(refreshTokenRepository.findAll()).hasSize(3);

        // Envelhece TODAS as linhas como revogadas/expiradas ha 40 dias.
        LocalDateTime ancient = LocalDateTime.now().minusDays(40);
        refreshTokenRepository.findAll().forEach(rt -> {
            rt.setRevokedAt(ancient);
            rt.setExpiresAt(ancient);
            refreshTokenRepository.save(rt);
        });

        int deleted = cleanupJob.run();

        assertThat(deleted).isEqualTo(3);
        assertThat(refreshTokenRepository.findAll()).isEmpty();
    }

    @Test
    void run_mantemCadeiaRecente_mesmoComOldRevoked() {
        // T1 revogado ha 40 dias, T2 ativo ha 1 dia (substituiu T1 ha 1 dia).
        String t1 = refreshTokenService.issue(user);
        refreshTokenService.rotate(t1);
        refreshTokenRepository.findAll().stream()
                .filter(rt -> rt.getReplacedBy() != null)
                .forEach(old -> {
                    // T1 ficou revogado ha 40 dias.
                    old.setRevokedAt(LocalDateTime.now().minusDays(40));
                    refreshTokenRepository.save(old);
                });

        int deleted = cleanupJob.run();

        // T1 sai (revogado ha mais de 30d). T2 fica (ativo).
        // O cleanup precisa limpar T1.replacedBy -> NULL antes de deletar para
        // nao violar o FK self-referencing.
        assertThat(deleted).isOne();
        assertThat(refreshTokenRepository.findAll())
                .singleElement()
                .satisfies(remaining -> assertThat(remaining.isActive()).isTrue());
    }
}
