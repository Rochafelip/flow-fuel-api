package com.devappmobile.flowfuel.push;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingPushNotificationServiceTest {

    private final LoggingPushNotificationService service = new LoggingPushNotificationService();

    @Test
    void sendPushToUser_qualquerPayload_naoLancaExcecao() {
        PushPayload payload = new PushPayload("Título", "Corpo", "flowfuel://home");

        assertThatCode(() -> service.sendPushToUser(1L, payload))
                .doesNotThrowAnyException();
    }
}
