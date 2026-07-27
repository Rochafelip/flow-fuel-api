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

    private MimeMessage captureSentMessage() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        // A real send() calls saveChanges() to sync headers (e.g. Content-Type) with the
        // body content before writing to the wire; the mocked sender never does, so the
        // top-level Content-Type header would otherwise still read the stale default.
        message.saveChanges();
        return message;
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
