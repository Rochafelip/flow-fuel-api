package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(RefreshTokenService.class)
class RefreshTokenServiceTest {

    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private RefreshTokenService refreshTokenService;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refreshTokenService, "refreshTokenTtlMs", 60_000L);
        user = userRepository.save(new User("rt@test.com", "hash", "RT"));
    }

    @Test
    void issue_persistAHash_naoPlaintext() {
        String plain = refreshTokenService.issue(user);

        assertThat(plain).isNotBlank();
        assertThat(refreshTokenRepository.findAll())
                .hasSize(1)
                .allSatisfy(rt -> assertThat(rt.getTokenHash()).isNotEqualTo(plain));
    }

    @Test
    void rotate_comTokenValido_emiteNovoEMarcaAntigoComoRevogado() {
        String original = refreshTokenService.issue(user);

        RefreshTokenService.RotationResult result = refreshTokenService.rotate(original);

        assertThat(result.user().getId()).isEqualTo(user.getId());
        assertThat(result.newRefreshToken()).isNotBlank().isNotEqualTo(original);
        // O antigo deve ficar revogado e apontar para o novo.
        assertThat(refreshTokenRepository.findAll())
                .filteredOn(rt -> rt.getReplacedBy() != null)
                .singleElement()
                .satisfies(old -> {
                    assertThat(old.isRevoked()).isTrue();
                    assertThat(old.getReplacedBy()).isNotNull();
                });
    }

    @Test
    void rotate_comTokenDesconhecido_lancaAuthRefreshInvalid() {
        assertThatThrownBy(() -> refreshTokenService.rotate("token-inexistente"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_REFRESH_INVALID);
    }

    @Test
    void rotate_comTokenJaRevogado_lancaAuthRefreshRevokedERevogaCadeiaInteira() {
        String t1 = refreshTokenService.issue(user);
        String t2 = refreshTokenService.rotate(t1).newRefreshToken();
        refreshTokenService.rotate(t2);
        // t1 e t2 ja estao revogados, o t3 atual esta ativo.
        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId()))
                .hasSize(1);

        // Reapresentar t1 (revogado) deve invalidar tudo.
        assertThatThrownBy(() -> refreshTokenService.rotate(t1))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_REFRESH_REVOKED);

        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId()))
                .isEmpty();
    }

    @Test
    void rotate_comTokenExpirado_lancaAuthRefreshExpired() {
        String plain = refreshTokenService.issue(user);
        refreshTokenRepository.findAll().forEach(rt -> {
            rt.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            refreshTokenRepository.save(rt);
        });

        assertThatThrownBy(() -> refreshTokenService.rotate(plain))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_REFRESH_EXPIRED);
    }

    @Test
    void revoke_invalidaApenasOTokenInformado() {
        String t1 = refreshTokenService.issue(user);
        String t2 = refreshTokenService.issue(user);

        refreshTokenService.revoke(t1);

        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId()))
                .hasSize(1);
        // Rotacionar t2 ainda deve funcionar.
        assertThat(refreshTokenService.rotate(t2).newRefreshToken()).isNotBlank();
    }

    @Test
    void revokeAllForUser_invalidaTodasAsSessoesAtivas() {
        refreshTokenService.issue(user);
        refreshTokenService.issue(user);
        refreshTokenService.issue(user);
        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId()))
                .hasSize(3);

        refreshTokenService.revokeAllForUser(user.getId());

        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId()))
                .isEmpty();
    }
}
