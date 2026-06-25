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
        String link = linkBaseUrl + "?token=" + activationToken
                + "&email=" + java.net.URLEncoder.encode(user.getEmail(), java.nio.charset.StandardCharsets.UTF_8);
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
            helper.setText(plainBody(greetingName, link, validity, activationToken),
                    htmlBody(greetingName, link, validity, activationToken));

            mailSender.send(message);
            log.info("[ACCOUNT-ACTIVATION] email enviado userId={} email={}", user.getId(), user.getEmail());
        } catch (MailException | MessagingException ex) {
            // Nao vazar o token; logar a falha para investigacao (Sentry via logback).
            log.error("[ACCOUNT-ACTIVATION] falha ao enviar email userId={} email={}",
                    user.getId(), user.getEmail(), ex);
            throw new IllegalStateException("Falha ao enviar email de ativacao", ex);
        }
    }

    private static String plainBody(String greetingName, String link, String validity, String activationToken) {
        return """
                Olá%s,

                Para ativar sua conta FlowFuel, toque no link abaixo no seu celular (válido por %s):

                %s

                Se o link não abrir o app, cole este código na tela de ativação:

                %s

                Se você não criou esta conta, ignore este email.

                — Equipe FlowFuel"""
                .formatted(greetingName, validity, link, activationToken);
    }

    private static String htmlBody(String greetingName, String link, String validity, String activationToken) {
        return """
                <!DOCTYPE html>
                <html lang="pt-BR">
                <body style="margin:0;padding:0;background-color:#f4f5f7;font-family:Arial,Helvetica,sans-serif;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f5f7;padding:32px 0;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="background-color:#ffffff;border-radius:12px;overflow:hidden;">
                          <tr>
                            <td style="background-color:#0d6efd;padding:24px 32px;">
                              <span style="color:#ffffff;font-size:22px;font-weight:bold;letter-spacing:0.5px;">FlowFuel</span>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:32px;">
                              <h1 style="margin:0 0 16px;font-size:20px;color:#1a1a2e;">Ative sua conta</h1>
                              <p style="margin:0 0 16px;font-size:15px;line-height:1.6;color:#444;">
                                Olá%s, falta só um passo para começar a usar a FlowFuel. Clique no botão abaixo para ativar sua conta.
                              </p>
                              <table role="presentation" cellpadding="0" cellspacing="0" style="margin:24px 0;">
                                <tr>
                                  <td style="border-radius:8px;background-color:#0d6efd;">
                                    <a href="%s" style="display:inline-block;padding:14px 28px;font-size:15px;font-weight:bold;color:#ffffff;text-decoration:none;border-radius:8px;">Ativar minha conta</a>
                                  </td>
                                </tr>
                              </table>
                              <p style="margin:0 0 8px;font-size:13px;color:#888;">
                                Este link é válido por %s. Se o botão não abrir o app, cole o código abaixo na tela de ativação:
                              </p>
                              <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 0 16px;">
                                <tr>
                                  <td style="background-color:#f4f5f7;border:1px solid #e0e0e0;border-radius:8px;padding:12px 16px;">
                                    <span style="font-family:'Courier New',monospace;font-size:15px;letter-spacing:0.5px;color:#1a1a2e;word-break:break-all;">%s</span>
                                  </td>
                                </tr>
                              </table>
                              <p style="margin:0;font-size:13px;color:#888;">
                                Se você não criou esta conta, pode ignorar este email com segurança.
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:20px 32px;border-top:1px solid #eee;">
                              <span style="font-size:12px;color:#aaa;">— Equipe FlowFuel</span>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>"""
                .formatted(greetingName, link, validity, activationToken);
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
