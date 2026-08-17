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
    void sendActivationCode_incluiCodigoNoHtml() throws Exception {
        notifier.sendActivationCode(buildUser("fulano@example.com"), "12345");

        String html = findPartContent(captureSentMessage(), "text/html");

        assertThat(html).contains("12345");
    }

    @Test
    void sendActivationCode_incluiCodigoNoPlainText() throws Exception {
        notifier.sendActivationCode(buildUser("fulano@example.com"), "12345");

        String plain = findPartContent(captureSentMessage(), "text/plain");

        assertThat(plain).contains("12345");
    }

    @Test
    void sendActivationCode_naoIncluiLinkOuUrl() throws Exception {
        notifier.sendActivationCode(buildUser("fulano@example.com"), "12345");

        MimeMessage message = captureSentMessage();
        String html = findPartContent(message, "text/html");
        String plain = findPartContent(message, "text/plain");

        assertThat(html).doesNotContain("http://").doesNotContain("https://");
        assertThat(plain).doesNotContain("http://").doesNotContain("https://");
    }
}
