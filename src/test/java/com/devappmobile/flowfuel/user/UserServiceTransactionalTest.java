package com.devappmobile.flowfuel.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class UserServiceTransactionalTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private RefreshTokenRepository refreshTokenRepository;

    private User user;
    private RefreshToken refreshToken;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        user = new User("change-password-tx@test.com", passwordEncoder.encode("OldPass123"), "User");
        user = userRepository.save(user);

        refreshToken = new RefreshToken(user, "tokenhash-tx-test", LocalDateTime.now().plusDays(1));
        refreshToken = refreshTokenRepository.save(refreshToken);
    }

    @Test
    void changePassword_falhaAoRevogarTokens_naoAlteraSenhaNemTokens() {
        String oldPasswordHash = user.getPassword();

        doThrow(new RuntimeException("falha simulada ao revogar tokens"))
                .when(refreshTokenRepository).revokeAllActiveByUserId(any(Long.class), any(LocalDateTime.class));

        assertThatThrownBy(() -> authService.changePassword(user.getId(), "OldPass123", "NewPass456"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("falha simulada ao revogar tokens");

        User reloadedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloadedUser.getPassword()).isEqualTo(oldPasswordHash);

        RefreshToken reloadedToken = refreshTokenRepository.findById(refreshToken.getId()).orElseThrow();
        assertThat(reloadedToken.isRevoked()).isFalse();
    }
}
