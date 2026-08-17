package com.devappmobile.flowfuel.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implementacao stub de {@link AccountActivationNotifier}: registra o codigo de
 * ativacao em log em vez de enviar email. E o fallback quando o envio real de
 * email esta desligado ({@code flowfuel.mail.enabled} ausente ou {@code false}),
 * tipicamente em dev / testes.
 *
 * <p>ATENCAO: em producao, ligue {@code flowfuel.mail.enabled=true} e configure o
 * SMTP — caso contrario o codigo de ativacao aparecera nos logs do servidor.
 */
@Component
@ConditionalOnProperty(name = "flowfuel.mail.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingAccountActivationNotifier implements AccountActivationNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingAccountActivationNotifier.class);

    @Override
    public void sendActivationCode(User user, String activationCode) {
        log.info("[ACCOUNT-ACTIVATION] (stub de email) código de ativação para userId={} email={}: {}",
                user.getId(), user.getEmail(), activationCode);
    }
}
