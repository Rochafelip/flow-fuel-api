package com.devappmobile.flowfuel.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implementacao stub de {@link PasswordResetNotifier}: registra o token em log
 * em vez de enviar email. E o fallback quando o envio real de email esta
 * desligado ({@code flowfuel.mail.enabled} ausente ou {@code false}),
 * tipicamente em dev / testes — mesmo padrao de
 * {@link LoggingAccountActivationNotifier}.
 *
 * <p>ATENCAO: em producao, ligue {@code flowfuel.mail.enabled=true} e configure o
 * SMTP — caso contrario o token de reset aparecera nos logs do servidor.
 */
@Component
@ConditionalOnProperty(name = "flowfuel.mail.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingPasswordResetNotifier implements PasswordResetNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetNotifier.class);

    @Override
    public void sendResetToken(User user, String resetToken) {
        log.info("[PASSWORD-RESET] (stub de email) token para userId={} email={}: {}",
                user.getId(), user.getEmail(), resetToken);
    }
}
