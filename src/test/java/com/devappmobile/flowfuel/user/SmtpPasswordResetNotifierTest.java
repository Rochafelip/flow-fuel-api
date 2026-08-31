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
class SmtpPasswordResetNotifierTest {

    @Mock
    private JavaMailSender mailSender;

    private SmtpPasswordResetNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new SmtpPasswordResetNotifier(mailSender);
        ReflectionTestUtils.setField(notifier, "from", "no-reply@flowfuel.app");
        ReflectionTestUtils.setField(notifier, "tokenTtlMinutes", 30L);
        ReflectionTestUtils.setField(notifier, "linkBaseUrl", "http://localhost:5173/reset-password");
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
        message.saveChanges();
        return message;
    }

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
    void sendResetToken_incluiLinkNoHtml() throws Exception {
        notifier.sendResetToken(buildUser("fulano@example.com"), "abc123token");

        String html = findPartContent(captureSentMessage(), "text/html");

        assertThat(html)
                .contains("http://localhost:5173/reset-password?")
                .contains("token=abc123token")
                .contains("email=fulano%40example.com");
    }

    @Test
    void sendResetToken_incluiLinkNoPlainText() throws Exception {
        notifier.sendResetToken(buildUser("fulano@example.com"), "abc123token");

        String plain = findPartContent(captureSentMessage(), "text/plain");

        assertThat(plain)
                .contains("http://localhost:5173/reset-password?")
                .contains("token=abc123token")
                .contains("email=fulano%40example.com");
    }

    @Test
    void sendResetToken_urlEncodeEmailComCaracteresEspeciais() throws Exception {
        notifier.sendResetToken(buildUser("fulano+teste@example.com"), "abc123token");

        String html = findPartContent(captureSentMessage(), "text/html");

        assertThat(html).contains("email=fulano%2Bteste%40example.com");
    }
}
