package com.devappmobile.flowfuel.user;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Implementacao real de {@link AccountActivationNotifier}: envia o link de
 * ativacao por email via {@link JavaMailSender} (SMTP). Ativa quando
 * {@code flowfuel.mail.enabled=true} (prod/staging).
 *
 * <p>Provider-agnostico: funciona com qualquer SMTP (SES, SendGrid, Gmail, ...),
 * configurado via {@code spring.mail.*}.
 */
@Component
@ConditionalOnProperty(name = "flowfuel.mail.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SmtpAccountActivationNotifier implements AccountActivationNotifier {

    private static final Logger log = LoggerFactory.getLogger(SmtpAccountActivationNotifier.class);

    private final JavaMailSender mailSender;

    @Value("${flowfuel.mail.from:no-reply@flowfuel.app}")
    private String from;

    @Value("${flowfuel.account-activation.link-base-url:http://localhost:5173/activate}")
    private String linkBaseUrl;

    @Override
    public void sendActivationLink(User user, String activationToken) {
        String link = linkBaseUrl + "?token=" + activationToken;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject("Ative sua conta FlowFuel");
        message.setText("""
                Olá%s,

                Para ativar sua conta FlowFuel, acesse o link abaixo (válido por tempo limitado):

                %s

                Se você não criou esta conta, ignore este email.

                — Equipe FlowFuel"""
                .formatted(user.getName() != null ? " " + user.getName() : "", link));

        try {
            mailSender.send(message);
            log.info("[ACCOUNT-ACTIVATION] email enviado userId={} email={}", user.getId(), user.getEmail());
        } catch (MailException ex) {
            // Nao vazar o token; logar a falha para investigacao (Sentry via logback).
            log.error("[ACCOUNT-ACTIVATION] falha ao enviar email userId={} email={}",
                    user.getId(), user.getEmail(), ex);
            throw ex;
        }
    }
}
