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

/**
 * Implementacao real de {@link PasswordResetNotifier}: envia o token de reset
 * por email via {@link JavaMailSender} (SMTP). Ativa quando
 * {@code flowfuel.mail.enabled=true} (prod/staging).
 *
 * <p>Mesmo padrao de {@link SmtpAccountActivationNotifier}: envia em
 * {@code multipart/alternative} (HTML + texto), provider-agnostico via
 * {@code spring.mail.*}.
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

    @Override
    public void sendResetToken(User user, String resetToken) {
        String greetingName = user.getName() != null ? " " + user.getName() : "";
        String validity = formatValidity(tokenTtlMinutes);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(user.getEmail());
            helper.setSubject("Redefinição de senha FlowFuel");
            helper.setText(plainBody(greetingName, validity, resetToken),
                    htmlBody(greetingName, validity, resetToken));

            mailSender.send(message);
            log.info("[PASSWORD-RESET] email enviado userId={} email={}", user.getId(), user.getEmail());
        } catch (MailException | MessagingException ex) {
            // Nao vazar o token; logar a falha para investigacao (Sentry via logback).
            log.error("[PASSWORD-RESET] falha ao enviar email userId={} email={}",
                    user.getId(), user.getEmail(), ex);
            throw new IllegalStateException("Falha ao enviar email de redefinição de senha", ex);
        }
    }

    private static String plainBody(String greetingName, String validity, String resetToken) {
        return """
                Olá%s,

                Recebemos uma solicitação para redefinir a senha da sua conta FlowFuel.
                Use o código abaixo (válido por %s):

                %s

                Digite esse código no app para escolher uma nova senha.

                Se você não solicitou esta redefinição, ignore este email — sua senha
                atual continua válida.

                — Equipe FlowFuel"""
                .formatted(greetingName, validity, resetToken);
    }

    private static String htmlBody(String greetingName, String validity, String resetToken) {
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
                                Use o código abaixo no app para escolher uma nova senha. Ele expira em %s.
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding-bottom:32px;" align="center">
                              <span style="display:inline-block;background-color:#f4f4f4;color:#111;font-size:16px;font-weight:700;letter-spacing:1px;padding:16px 24px;border-radius:8px;word-break:break-all;">%s</span>
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
                .formatted(greetingName, validity, resetToken);
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
