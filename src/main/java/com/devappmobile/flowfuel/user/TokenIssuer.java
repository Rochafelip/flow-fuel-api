package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.config.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Emite o par access+refresh token para um usuario autenticado.
 * Extraido de AuthService para ser reutilizavel por AccountActivationService
 * sem criar dependencia circular (AuthService -> AccountActivationService).
 */
@Component
@RequiredArgsConstructor
public class TokenIssuer {

    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    public TokenPairResponse issueTokenPair(User user) {
        String accessToken = jwtUtil.generateToken(user.getEmail(), user.getId());
        String refreshToken = refreshTokenService.issue(user);
        return new TokenPairResponse(accessToken, refreshToken,
                jwtUtil.getAccessTokenTtlMs() / 1000);
    }
}
