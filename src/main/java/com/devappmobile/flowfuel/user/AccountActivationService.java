package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Fluxo de ativacao de conta (confirmacao de email).
 *
 * <p>Segue o mesmo padrao do {@link PasswordResetService} (ADR-003): o token e
 * opaco (32 bytes aleatorios em base64-url) e no banco gravamos apenas seu
 * SHA-256. E de uso unico e curta duracao.
 *
 * <p>Anti-enumeracao: {@link #resendActivation(String)} responde sempre da mesma
 * forma, exista ou nao o email e esteja ou nao a conta pendente.
 */
@Service
@RequiredArgsConstructor
public class AccountActivationService {

    private static final Logger log = LoggerFactory.getLogger(AccountActivationService.class);
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository userRepository;
    private final ActivationTokenRepository tokenRepository;
    private final AccountActivationNotifier notifier;

    @Value("${flowfuel.account-activation.token-ttl-minutes:60}")
    private long tokenTtlMinutes;

    @Value("${flowfuel.account-activation.expose-token:false}")
    private boolean exposeToken;

    /**
     * Emite e envia um novo token de ativacao para o usuario. Cada emissao
     * invalida os tokens ativos anteriores (apenas o ultimo enviado e valido).
     * Retorna o plaintext (usado apenas para expor em dev/testes).
     */
    @Transactional
    public String sendActivation(User user) {
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.invalidateActiveByUserId(user.getId(), now);

        String plaintext = generatePlaintext();
        tokenRepository.save(new ActivationToken(user, sha256(plaintext),
                now.plusMinutes(tokenTtlMinutes)));

        notifier.sendActivationLink(user, plaintext);
        return plaintext;
    }

    /**
     * Ativa a conta a partir de um token valido. Apos sucesso o token e consumido
     * (uso unico) e o usuario passa a {@link UserStatus#ACTIVE}.
     */
    @Transactional
    public void activate(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new AppException(ErrorCode.AUTH_ACTIVATION_INVALID, "Token de ativação ausente");
        }

        ActivationToken token = tokenRepository.findByTokenHash(sha256(plaintext))
                .filter(ActivationToken::isUsable)
                .orElseThrow(() -> new AppException(ErrorCode.AUTH_ACTIVATION_INVALID,
                        "Token de ativação inválido ou expirado"));

        User user = token.getUser();
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        token.setUsedAt(LocalDateTime.now());
        log.info("Conta ativada via token userId={}", user.getId());
    }

    /**
     * Reenvia o link de ativacao para um email. Resposta identica exista ou nao o
     * email e esteja ou nao a conta pendente (anti-enumeracao). So reenvia de fato
     * se a conta existe e ainda esta {@link UserStatus#PENDING_ACTIVATION}.
     */
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

    private static String generatePlaintext() {
        byte[] buf = new byte[TOKEN_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }
}
