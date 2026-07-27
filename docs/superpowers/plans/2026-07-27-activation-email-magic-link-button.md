# Activation Email Magic-Link Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the copy/paste activation code in the account-activation email with a clickable magic-link button/URL, so opening the email and tapping once is enough to activate the account (no manual code entry).

**Architecture:** `SmtpAccountActivationNotifier` builds a URL of the form `{linkBaseUrl}?token={activationToken}&email={urlEncodedEmail}` — the same shape already logged by `LoggingAccountActivationNotifier` — and renders it as an `<a href>` button in the HTML body and as a raw clickable URL in the plain-text body, replacing the current monospace code block. No endpoint, service, or token-generation logic changes.

**Tech Stack:** Java 17+, Spring Boot (`JavaMailSender`, `MimeMessageHelper`), JUnit 5, Mockito, AssertJ.

---

### Task 1: Write failing tests for the link-based email

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/user/SmtpAccountActivationNotifierTest.java`

- [ ] **Step 1: Write the test file**

```java
package com.devappmobile.flowfuel.user;

import jakarta.mail.Part;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.mail.Session;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmtpAccountActivationNotifierTest {

    @Mock
    private JavaMailSender mailSender;

    private SmtpAccountActivationNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new SmtpAccountActivationNotifier(mailSender);
        ReflectionTestUtils.setField(notifier, "from", "no-reply@flowfuel.app");
        ReflectionTestUtils.setField(notifier, "linkBaseUrl", "https://app.flowfuel.app/activate");
        ReflectionTestUtils.setField(notifier, "tokenTtlMinutes", 60L);
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getDefaultInstance(new Properties())));
    }

    private User buildUser(String email) {
        User user = new User(email, "hashed", "Fulano");
        user.setId(1L);
        return user;
    }

    private MimeMessage captureSentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    /** Recursively finds the first body part whose MIME type matches (e.g. "text/html"). */
    private String findPartContent(Part part, String mimeType) throws Exception {
        if (part.isMimeType(mimeType)) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                String found = findPartContent(multipart.getBodyPart(i), mimeType);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void sendActivationLink_incluiUrlComTokenEEmailNoHtml() throws Exception {
        notifier.sendActivationLink(buildUser("fulano@example.com"), "abc123token");

        String html = findPartContent(captureSentMessage(), "text/html");

        assertThat(html).contains(
                "https://app.flowfuel.app/activate?token=abc123token&email=fulano%40example.com");
    }

    @Test
    void sendActivationLink_incluiUrlComTokenEEmailNoPlainText() throws Exception {
        notifier.sendActivationLink(buildUser("fulano@example.com"), "abc123token");

        String plain = findPartContent(captureSentMessage(), "text/plain");

        assertThat(plain).contains(
                "https://app.flowfuel.app/activate?token=abc123token&email=fulano%40example.com");
    }

    @Test
    void sendActivationLink_naoExpoeMaisOCodigoBrutoForaDaUrl() throws Exception {
        String token = "abc123token";

        notifier.sendActivationLink(buildUser("fulano@example.com"), token);

        MimeMessage message = captureSentMessage();
        String html = findPartContent(message, "text/html");
        String plain = findPartContent(message, "text/plain");

        String htmlWithoutUrl = html.replace("token=" + token, "");
        String plainWithoutUrl = plain.replace("token=" + token, "");
        assertThat(htmlWithoutUrl).doesNotContain(token);
        assertThat(plainWithoutUrl).doesNotContain(token);
    }

    @Test
    void sendActivationLink_codificaEmailComCaracteresEspeciais() throws Exception {
        notifier.sendActivationLink(buildUser("fulano+teste@example.com"), "abc123token");

        String html = findPartContent(captureSentMessage(), "text/html");

        assertThat(html).contains("email=fulano%2Bteste%40example.com");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=SmtpAccountActivationNotifierTest`

Expected: 4 failures — `sendActivationLink_incluiUrlComTokenEEmailNoHtml` and `..NoPlainText` fail because the current email body doesn't contain a URL at all (just the raw token in a `<span>`/plain block); `..naoExpoeMaisOCodigoBrutoForaDaUrl` fails because the token appears outside any URL today; `..codificaEmailComCaracteresEspeciais` fails because the email address isn't in the body at all today.

---

### Task 2: Implement the magic-link email body

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/SmtpAccountActivationNotifier.java:1-148`

- [ ] **Step 1: Add imports and build the activation URL**

Add these imports at the top (alongside the existing ones):

```java
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
```

Replace the `sendActivationLink` method body (lines 47-71) with:

```java
    @Override
    public void sendActivationLink(User user, String activationToken) {
        String greetingName = user.getName() != null ? " " + user.getName() : "";
        String validity = formatValidity(tokenTtlMinutes);
        String activationUrl = buildActivationUrl(user.getEmail(), activationToken);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            // true = multipart (HTML + texto); UTF-8 para acentos.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(user.getEmail());
            helper.setSubject("Ative sua conta FlowFuel");
            // setText(plain, html): o cliente escolhe o melhor que conseguir renderizar.
            helper.setText(plainBody(greetingName, validity, activationUrl),
                    htmlBody(greetingName, validity, activationUrl));

            mailSender.send(message);
            log.info("[ACCOUNT-ACTIVATION] email enviado userId={} email={}", user.getId(), user.getEmail());
        } catch (MailException | MessagingException ex) {
            // Nao vazar o token; logar a falha para investigacao (Sentry via logback).
            log.error("[ACCOUNT-ACTIVATION] falha ao enviar email userId={} email={}",
                    user.getId(), user.getEmail(), ex);
            throw new IllegalStateException("Falha ao enviar email de ativacao", ex);
        }
    }

    /** Mesmo formato de URL usado por {@link LoggingAccountActivationNotifier}. */
    private String buildActivationUrl(String email, String activationToken) {
        String encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8);
        return linkBaseUrl + "?token=" + activationToken + "&email=" + encodedEmail;
    }
```

- [ ] **Step 2: Replace `plainBody` to link instead of showing a raw code**

Replace lines 73-85 with:

```java
    private static String plainBody(String greetingName, String validity, String activationUrl) {
        return """
                Olá%s,

                Clique no link abaixo para ativar sua conta FlowFuel (válido por %s):

                %s

                Se você não criou esta conta, ignore este email.

                — Equipe FlowFuel"""
                .formatted(greetingName, validity, activationUrl);
    }
```

- [ ] **Step 3: Replace `htmlBody` to render a button instead of a code block**

Replace lines 87-138 with:

```java
    private static String htmlBody(String greetingName, String validity, String activationUrl) {
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
                                Clique no botão abaixo para ativar sua conta. O link expira em %s.
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding-bottom:32px;" align="center">
                              <a href="%s" style="display:inline-block;background-color:#111;color:#ffffff;font-size:16px;font-weight:600;text-decoration:none;padding:16px 32px;border-radius:8px;">Ativar conta</a>
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
                .formatted(greetingName, validity, activationUrl);
    }
```

- [ ] **Step 4: Run the new tests and verify they pass**

Run: `mvn -q test -Dtest=SmtpAccountActivationNotifierTest`

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Run the full `user` module test suite to check for regressions**

Run: `mvn -q test -Dtest='com.devappmobile.flowfuel.user.*Test'`

Expected: all tests pass (no test in this package references the old code-block copy — confirmed by prior search — so nothing else should break).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/SmtpAccountActivationNotifier.java \
        src/test/java/com/devappmobile/flowfuel/user/SmtpAccountActivationNotifierTest.java
git commit -m "$(cat <<'EOF'
feat(user): send activation email as a one-click magic-link button

Replaces the copy/paste activation code with a clickable link
(linkBaseUrl?token=...&email=...), matching the URL shape already
used by LoggingAccountActivationNotifier. No endpoint or token
contract changes.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** URL format matches `LoggingAccountActivationNotifier` (spec decision "email as query param"); raw code removed from both bodies (spec decision "só o botão"); no endpoint/service/token changes (spec "fora de escopo"); new test file covers all 4 acceptance-criteria-relevant behaviors from the spec's test plan.
- **No placeholders:** every step shows complete, runnable code.
- **Type/signature consistency:** `sendActivationLink(User, String)` signature unchanged (matches `AccountActivationNotifier` interface and existing callers in `AccountActivationService`); `buildActivationUrl`, `plainBody`, `htmlBody` signatures are consistent between Task 2's steps.
