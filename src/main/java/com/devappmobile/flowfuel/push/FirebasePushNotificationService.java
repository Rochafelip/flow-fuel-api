package com.devappmobile.flowfuel.push;

import com.devappmobile.flowfuel.devicetoken.DeviceToken;
import com.devappmobile.flowfuel.devicetoken.DeviceTokenRepository;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementacao real de {@link PushNotificationService} usando Firebase Admin
 * SDK. Ativa apenas quando {@code flowfuel.push.enabled=true}.
 *
 * <p>Tokens reportados pelo FCM como {@link MessagingErrorCode#UNREGISTERED}
 * sao removidos do repositorio (nao existem mais no lado do dispositivo).
 * Demais erros (ex: {@code UNAVAILABLE}) sao tratados como falhas
 * transitorias e apenas logados, sem remover o token.
 */
@Component
@ConditionalOnProperty(name = "flowfuel.push.enabled", havingValue = "true")
@RequiredArgsConstructor
public class FirebasePushNotificationService implements PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(FirebasePushNotificationService.class);

    private final FirebaseMessaging firebaseMessaging;
    private final DeviceTokenRepository deviceTokenRepository;

    @Override
    public void sendPushToUser(Long userId, PushPayload payload) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            return;
        }

        Map<String, String> data = new HashMap<>();
        putIfPresent(data, "title", payload.title());
        putIfPresent(data, "body", payload.body());
        putIfPresent(data, "deepLink", payload.deepLink());
        putIfPresent(data, "type", payload.type());

        for (DeviceToken deviceToken : tokens) {
            sendToToken(userId, deviceToken, data);
        }
    }

    private static void putIfPresent(Map<String, String> data, String key, String value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private void sendToToken(Long userId, DeviceToken deviceToken, Map<String, String> data) {
        Message message = Message.builder()
                .setToken(deviceToken.getToken())
                .putAllData(data)
                .build();
        try {
            firebaseMessaging.send(message);
        } catch (FirebaseMessagingException ex) {
            if (ex.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                log.warn("[PUSH] token invalido removido userId={}", userId);
                deviceTokenRepository.delete(deviceToken);
            } else {
                log.error("[PUSH] falha ao enviar push userId={}", userId, ex);
            }
        } catch (RuntimeException ex) {
            // Nao deixa uma falha em um token (ex: Message malformada) abortar
            // o processamento dos demais tokens do usuario.
            log.error("[PUSH] falha inesperada ao enviar push userId={}", userId, ex);
        }
    }
}
