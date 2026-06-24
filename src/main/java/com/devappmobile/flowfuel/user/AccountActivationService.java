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
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccountActivationService {

    private static final Logger log = LoggerFactory.getLogger(AccountActivationService.class);

    private final UserRepository userRepository;
    private final ActivationTokenRepository tokenRepository;
    private final AccountActivationNotifier notifier;
    private final TokenIssuer tokenIssuer;

    @Value("${flowfuel.account-activation.token-ttl-minutes:60}")
    private long tokenTtlMinutes;

    @Value("${flowfuel.account-activation.expose-token:false}")
    private boolean exposeToken;

    @Transactional
    public String sendActivation(User user) {
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.invalidateActiveByUserId(user.getId(), now);

        String plaintext = OpaqueTokenGenerator.generatePlaintext();
        tokenRepository.save(new ActivationToken(user, OpaqueTokenGenerator.sha256(plaintext),
                now.plusMinutes(tokenTtlMinutes)));

        notifier.sendActivationLink(user, plaintext);
        return plaintext;
    }

    @Transactional
    public TokenPairResponse activate(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new AppException(ErrorCode.AUTH_ACTIVATION_INVALID, "Token de ativação ausente");
        }

        ActivationToken token = tokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext))
                .filter(ActivationToken::isUsable)
                .orElseThrow(() -> new AppException(ErrorCode.AUTH_ACTIVATION_INVALID,
                        "Token de ativação inválido ou expirado"));

        User user = token.getUser();
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        token.setUsedAt(LocalDateTime.now());
        log.info("Conta ativada via token userId={}", user.getId());

        return tokenIssuer.issueTokenPair(user);
    }

    @Transactional
    public AccountActivationResponse resendActivation(String email) {
        AccountActivationResponse response = AccountActivationResponse.standard();

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty() || userOpt.get().isActive()) {
            log.info("Reenvio de ativacao ignorado (email inexistente ou conta ja ativa)");
            return response;
        }

        String plaintext = sendActivation(userOpt.get());
        return exposeToken ? response.withToken(plaintext) : response;
    }
}
