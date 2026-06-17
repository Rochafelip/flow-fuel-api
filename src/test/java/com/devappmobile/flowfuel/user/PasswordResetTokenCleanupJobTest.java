package com.devappmobile.flowfuel.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "flowfuel.password-reset.cleanup.enabled=true")
@Import(PasswordResetTokenCleanupJob.class)
class PasswordResetTokenCleanupJobTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordResetTokenRepository tokenRepository;
    @Autowired private PasswordResetTokenCleanupJob cleanupJob;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(cleanupJob, "retentionDays", 7);
        user = userRepository.save(new User("cleanup-pr@test.com", "hash", "Cleanup"));
    }

    @Test
    void run_naoDeletaTokensAtivos() {
        tokenRepository.save(new PasswordResetToken(user, "hash1", LocalDateTime.now().plusHours(1)));

        int deleted = cleanupJob.run();

        assertThat(deleted).isZero();
        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    void run_deletaTokensExpiradosHaMaisDeRetencao() {
        tokenRepository.save(new PasswordResetToken(user, "hash2", LocalDateTime.now().minusDays(8)));

        int deleted = cleanupJob.run();

        assertThat(deleted).isOne();
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    void run_naoDeletaExpiradosDentroDaJanelaDeRetencao() {
        tokenRepository.save(new PasswordResetToken(user, "hash3", LocalDateTime.now().minusDays(3)));

        int deleted = cleanupJob.run();

        assertThat(deleted).isZero();
        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    void run_deletaTokensUsadosHaMaisDeRetencao() {
        PasswordResetToken token = new PasswordResetToken(user, "hash4", LocalDateTime.now().plusHours(1));
        token.setUsedAt(LocalDateTime.now().minusDays(8));
        tokenRepository.save(token);

        int deleted = cleanupJob.run();

        assertThat(deleted).isOne();
        assertThat(tokenRepository.findAll()).isEmpty();
    }
}
