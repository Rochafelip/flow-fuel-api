package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.config.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenIssuerTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private RefreshTokenService refreshTokenService;

    @InjectMocks private TokenIssuer tokenIssuer;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User("test@example.com", "hashed_password", "Test User");
        user.setId(1L);
    }

    @Test
    void issueTokenPair_retornaAccessTokenERefreshToken() {
        when(jwtUtil.generateToken("test@example.com", 1L)).thenReturn("jwt-token-gerado");
        when(jwtUtil.getAccessTokenTtlMs()).thenReturn(900_000L);
        when(refreshTokenService.issue(user)).thenReturn("refresh-plain");

        TokenPairResponse response = tokenIssuer.issueTokenPair(user);

        assertThat(response.accessToken()).isEqualTo("jwt-token-gerado");
        assertThat(response.refreshToken()).isEqualTo("refresh-plain");
        assertThat(response.expiresIn()).isEqualTo(900L);
    }

    @Test
    void issueTokenPair_usaTtlConfiguradoDoJwtUtil() {
        when(jwtUtil.generateToken("test@example.com", 1L)).thenReturn("jwt-token");
        when(jwtUtil.getAccessTokenTtlMs()).thenReturn(1_800_000L);
        when(refreshTokenService.issue(user)).thenReturn("refresh-plain");

        TokenPairResponse response = tokenIssuer.issueTokenPair(user);

        assertThat(response.expiresIn()).isEqualTo(1800L);
    }
}
