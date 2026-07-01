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
 * Implementacao real de {@link AccountActivationNotifier}: envia o link de
 * ativacao por email via {@link JavaMailSender} (SMTP). Ativa quando
 * {@code flowfuel.mail.enabled=true} (prod/staging).
 *
 * <p>Envia em {@code multipart/alternative}: uma versao HTML (com botao) e um
 * fallback em texto puro, para clientes que nao renderizam HTML e melhor
 * entregabilidade.
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

    // Mesmo valor que o AccountActivationService usa para o TTL do token, para
    // que o prazo exibido no email seja sempre coerente com o real.
    @Value("${flowfuel.account-activation.token-ttl-minutes:60}")
    private long tokenTtlMinutes;

    @Override
    public void sendActivationLink(User user, String activationToken) {
        String greetingName = user.getName() != null ? " " + user.getName() : "";
        String validity = formatValidity(tokenTtlMinutes);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            // true = multipart (HTML + texto); UTF-8 para acentos.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(user.getEmail());
            helper.setSubject("Ative sua conta FlowFuel");
            // setText(plain, html): o cliente escolhe o melhor que conseguir renderizar.
            helper.setText(plainBody(greetingName, validity, activationToken),
                    htmlBody(greetingName, validity, activationToken));

            mailSender.send(message);
            log.info("[ACCOUNT-ACTIVATION] email enviado userId={} email={}", user.getId(), user.getEmail());
        } catch (MailException | MessagingException ex) {
            // Nao vazar o token; logar a falha para investigacao (Sentry via logback).
            log.error("[ACCOUNT-ACTIVATION] falha ao enviar email userId={} email={}",
                    user.getId(), user.getEmail(), ex);
            throw new IllegalStateException("Falha ao enviar email de ativacao", ex);
        }
    }

    private static String plainBody(String greetingName, String validity, String activationToken) {
        return """
                Olá%s,

                Cole o código abaixo na tela de ativação do app FlowFuel (válido por %s):

                %s

                Se você não criou esta conta, ignore este email.

                — Equipe FlowFuel"""
                .formatted(greetingName, validity, activationToken);
    }

    private static String htmlBody(String greetingName, String validity, String activationToken) {
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
                              <p style="margin:0;font-size:22px;font-weight:600;color:#111;line-height:1.3;">Ative sua conta%s</p>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding-bottom:32px;">
                              <p style="margin:0;font-size:15px;color:#555;line-height:1.6;">
                                Use o código abaixo para ativar sua conta. Ele expira em %s.
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding-bottom:32px;">
                              <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                                <tr>
                                  <td style="background-color:#f5f5f5;border-radius:8px;padding:24px;text-align:center;">
                                    <span style="font-family:'Courier New',Courier,monospace;font-size:32px;font-weight:700;letter-spacing:8px;color:#111;user-select:all;">%s</span>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="border-top:1px solid #eee;padding-top:24px;">
                              <p style="margin:0;font-size:13px;color:#999;line-height:1.6;">
                                Se você não criou esta conta, ignore este email.
                              </p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>"""
                .formatted(greetingName, validity, activationToken);
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
