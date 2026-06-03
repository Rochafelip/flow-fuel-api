package com.devappmobile.flowfuel.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementacao stub de {@link PasswordResetNotifier}: registra o token em log
 * em vez de enviar email. Suficiente para desenvolvimento e para o fluxo
 * funcionar ponta a ponta enquanto a infraestrutura de email nao existe.
 *
 * <p>ATENCAO: em producao, troque por uma implementacao real de email — caso
 * contrario o token de reset aparecera nos logs do servidor.
 */
@Component
public class LoggingPasswordResetNotifier implements PasswordResetNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetNotifier.class);

    @Override
    public void sendResetToken(User user, String resetToken) {
        log.info("[PASSWORD-RESET] (stub de email) token para userId={} email={}: {}",
                user.getId(), user.getEmail(), resetToken);
    }
}
