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
    void sendResetToken_incluiTokenNoHtml() throws Exception {
        notifier.sendResetToken(buildUser("fulano@example.com"), "abc123token");

        String html = findPartContent(captureSentMessage(), "text/html");

        assertThat(html).contains("abc123token");
    }

    @Test
    void sendResetToken_incluiTokenNoPlainText() throws Exception {
        notifier.sendResetToken(buildUser("fulano@example.com"), "abc123token");

        String plain = findPartContent(captureSentMessage(), "text/plain");

        assertThat(plain).contains("abc123token");
    }

    @Test
    void sendResetToken_naoIncluiLinkOuUrl() throws Exception {
        notifier.sendResetToken(buildUser("fulano@example.com"), "abc123token");

        MimeMessage message = captureSentMessage();
        String html = findPartContent(message, "text/html");
        String plain = findPartContent(message, "text/plain");

        assertThat(html).doesNotContain("http://").doesNotContain("https://");
        assertThat(plain).doesNotContain("http://").doesNotContain("https://");
    }
}
