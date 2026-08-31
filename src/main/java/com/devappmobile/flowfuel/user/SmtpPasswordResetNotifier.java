package com.devappmobile.flowfuel.user;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Implementacao real de {@link PasswordResetNotifier}: envia um link de reset
 * por email via {@link JavaMailSender} (SMTP). Ativa quando
 * {@code flowfuel.mail.enabled=true} (prod/staging).
 *
 * <p>Mesmo padrao de {@link SmtpAccountActivationNotifier}: envia em
 * {@code multipart/alternative} (HTML + texto), provider-agnostico via
 * {@code spring.mail.*}. Diferente da ativacao (que usa codigo numerico), o
 * reset de senha usa um token opaco longo — nao pratico para digitacao manual
 * — entao o email traz um botao/link que abre {@code linkBaseUrl} com
 * {@code ?token=...&email=...}, mesmo padrao que existiu no email de
 * ativacao antes da mudanca para codigo (ver historico git, commit 9b54f3d).
 */
@Component
@ConditionalOnProperty(name = "flowfuel.mail.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SmtpPasswordResetNotifier implements PasswordResetNotifier {

    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetNotifier.class);

    private final JavaMailSender mailSender;

    @Value("${flowfuel.mail.from:no-reply@flowfuel.app}")
    private String from;

    // Mesmo valor que o PasswordResetService usa para o TTL do token, para que
    // o prazo exibido no email seja sempre coerente com o real.
    @Value("${flowfuel.password-reset.token-ttl-minutes:30}")
    private long tokenTtlMinutes;

    @Value("${flowfuel.password-reset.link-base-url:http://localhost:5173/reset-password}")
    private String linkBaseUrl;

    @Override
    public void sendResetToken(User user, String resetToken) {
        String greetingName = user.getName() != null ? " " + user.getName() : "";
        String validity = formatValidity(tokenTtlMinutes);
        String resetUrl = buildResetUrl(user.getEmail(), resetToken);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(user.getEmail());
            helper.setSubject("Redefinição de senha FlowFuel");
            helper.setText(plainBody(greetingName, validity, resetUrl),
                    htmlBody(greetingName, validity, resetUrl));

            mailSender.send(message);
            log.info("[PASSWORD-RESET] email enviado userId={} email={}", user.getId(), user.getEmail());
        } catch (MailException | MessagingException ex) {
            // Nao vazar o token; logar a falha para investigacao (Sentry via logback).
            log.error("[PASSWORD-RESET] falha ao enviar email userId={} email={}",
                    user.getId(), user.getEmail(), ex);
            throw new IllegalStateException("Falha ao enviar email de redefinição de senha", ex);
        }
    }

    private String buildResetUrl(String email, String resetToken) {
        String encodedToken = URLEncoder.encode(resetToken, StandardCharsets.UTF_8);
        String encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8);
        return "%s?token=%s&email=%s".formatted(linkBaseUrl, encodedToken, encodedEmail);
    }

    private static String plainBody(String greetingName, String validity, String resetUrl) {
        return """
                Olá%s,

                Recebemos uma solicitação para redefinir a senha da sua conta FlowFuel.
                Clique no link abaixo para escolher uma nova senha (válido por %s):

                %s

                Se você não solicitou esta redefinição, ignore este email — sua senha
                atual continua válida.

                — Equipe FlowFuel"""
                .formatted(greetingName, validity, resetUrl);
    }

    private static String htmlBody(String greetingName, String validity, String resetUrl) {
        return """
                <!DOCTYPE html>
                <html lang="pt-BR">
                <body style="margin:0;padding:0;background-color:#ffffff;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="padding:48px 16px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="420" cellpadding="0" cellspacing="0">
                          <tr>
                            <td style="padding-bottom:32px;">
                              <span style="font-size:18px;font-weight:700;color:#111;">FlowFuel</span>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding-bottom:8px;">
                              <p style="margin:0;font-size:22px;font-weight:600;color:#111;line-height:1.3;">Redefinir senha%s</p>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding-bottom:32px;">
                              <p style="margin:0;font-size:15px;color:#555;line-height:1.6;">
                                Clique no botão abaixo para escolher uma nova senha. O link expira em %s.
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding-bottom:24px;" align="center">
                              <a href="%s" style="display:inline-block;background-color:#16a34a;color:#ffffff;font-size:16px;font-weight:700;padding:14px 32px;border-radius:8px;text-decoration:none;">Redefinir senha</a>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding-bottom:32px;">
                              <p style="margin:0;font-size:12px;color:#999;line-height:1.6;word-break:break-all;">
                                Se o botão não funcionar, copie e cole este link no navegador:<br>%s
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td style="border-top:1px solid #eee;padding-top:24px;">
                              <p style="margin:0;font-size:13px;color:#999;line-height:1.6;">
                                Se você não solicitou esta redefinição, ignore este email — sua senha atual continua válida.
                              </p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>"""
                .formatted(greetingName, validity, resetUrl, resetUrl);
    }

    /** Converte o TTL em minutos numa frase amigavel: "1 hora", "2 horas", "30 minutos". */
    private static String formatValidity(long minutes) {
        if (minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours == 1 ? "1 hora" : hours + " horas";
        }
        return minutes + " minutos";
    }
}
