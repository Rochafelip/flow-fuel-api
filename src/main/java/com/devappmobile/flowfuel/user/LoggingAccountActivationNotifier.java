package com.devappmobile.flowfuel.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Implementacao stub de {@link AccountActivationNotifier}: registra o link de
 * ativacao em log em vez de enviar email. E o fallback quando o envio real de
 * email esta desligado ({@code flowfuel.mail.enabled} ausente ou {@code false}),
 * tipicamente em dev / testes.
 *
 * <p>ATENCAO: em producao, ligue {@code flowfuel.mail.enabled=true} e configure o
 * SMTP — caso contrario o token de ativacao aparecera nos logs do servidor.
 */
@Component
@ConditionalOnProperty(name = "flowfuel.mail.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingAccountActivationNotifier implements AccountActivationNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingAccountActivationNotifier.class);

    @Value("${flowfuel.account-activation.link-base-url:http://localhost:5173/activate}")
    private String linkBaseUrl;

    @Override
    public void sendActivationLink(User user, String activationToken) {
        String encodedEmail = URLEncoder.encode(user.getEmail(), StandardCharsets.UTF_8);
        log.info("[ACCOUNT-ACTIVATION] (stub de email) link para userId={} email={}: {}?token={}&email={}",
                user.getId(), user.getEmail(), linkBaseUrl, activationToken, encodedEmail);
    }
}
