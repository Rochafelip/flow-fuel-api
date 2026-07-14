package com.devappmobile.flowfuel.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implementacao stub de {@link PushNotificationService}: registra o payload em
 * log em vez de enviar push real. E o fallback quando o envio real esta
 * desligado ({@code flowfuel.push.enabled} ausente ou {@code false}),
 * tipicamente em dev / testes.
 */
@Component
@ConditionalOnProperty(name = "flowfuel.push.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingPushNotificationService implements PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushNotificationService.class);

    @Override
    public void sendPushToUser(Long userId, PushPayload payload) {
        log.info("[PUSH] (stub) userId={} title={} deepLink={}", userId, payload.title(), payload.deepLink());
    }
}
