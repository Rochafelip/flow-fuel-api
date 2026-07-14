package com.devappmobile.flowfuel.push;

import com.devappmobile.flowfuel.devicetoken.DeviceToken;
import com.devappmobile.flowfuel.devicetoken.DeviceTokenRepository;
import com.devappmobile.flowfuel.devicetoken.DevicePlatform;
import com.devappmobile.flowfuel.user.User;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirebasePushNotificationServiceTest {

    @Mock
    private FirebaseMessaging firebaseMessaging;

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @InjectMocks
    private FirebasePushNotificationService pushNotificationService;

    private DeviceToken deviceToken;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(1L);
        deviceToken = new DeviceToken();
        deviceToken.setToken("token-valido");
        deviceToken.setUser(user);
        deviceToken.setPlatform(DevicePlatform.ANDROID);
    }

    @Test
    void sendPushToUser_tokenValido_enviaMensagemParaCadaToken() throws Exception {
        when(deviceTokenRepository.findByUserId(1L)).thenReturn(List.of(deviceToken));

        pushNotificationService.sendPushToUser(1L, new PushPayload("Título", "Corpo", "flowfuel://home"));

        verify(firebaseMessaging).send(any(Message.class));
        verify(deviceTokenRepository, never()).delete(any());
    }

    @Test
    void sendPushToUser_tokenReportadoUnregistered_removeToken() throws Exception {
        when(deviceTokenRepository.findByUserId(1L)).thenReturn(List.of(deviceToken));
        FirebaseMessagingException excecao = mock(FirebaseMessagingException.class);
        when(excecao.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(firebaseMessaging.send(any(Message.class))).thenThrow(excecao);

        pushNotificationService.sendPushToUser(1L, new PushPayload("Título", "Corpo", "flowfuel://home"));

        verify(deviceTokenRepository).delete(deviceToken);
    }

    @Test
    void sendPushToUser_falhaTransitoria_naoRemoveToken() throws Exception {
        when(deviceTokenRepository.findByUserId(1L)).thenReturn(List.of(deviceToken));
        FirebaseMessagingException excecao = mock(FirebaseMessagingException.class);
        when(excecao.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNAVAILABLE);
        when(firebaseMessaging.send(any(Message.class))).thenThrow(excecao);

        pushNotificationService.sendPushToUser(1L, new PushPayload("Título", "Corpo", "flowfuel://home"));

        verify(deviceTokenRepository, never()).delete(any());
    }

    @Test
    void sendPushToUser_usuarioSemTokens_naoChamaFirebase() {
        when(deviceTokenRepository.findByUserId(1L)).thenReturn(List.of());

        pushNotificationService.sendPushToUser(1L, new PushPayload("Título", "Corpo", "flowfuel://home"));

        verifyNoMoreFirebaseInteractions();
    }

    private void verifyNoMoreFirebaseInteractions() {
        org.mockito.Mockito.verifyNoInteractions(firebaseMessaging);
    }
}
