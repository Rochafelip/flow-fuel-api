package com.devappmobile.flowfuel.push;

/**
 * Envio de push notification a todos os dispositivos registrados de um usuario.
 *
 * <p>Ponto de extensao deliberado (mesmo padrao de
 * {@link com.devappmobile.flowfuel.user.AccountActivationNotifier}): o stub
 * {@link LoggingPushNotificationService} apenas registra o payload em log, e
 * {@link FirebasePushNotificationService} envia o push real via Firebase Admin
 * SDK. O bean ativo e escolhido por configuracao
 * ({@code flowfuel.push.enabled}) — nada no fluxo precisa mudar.
 */
public interface PushNotificationService {
    void sendPushToUser(Long userId, PushPayload payload);
}
