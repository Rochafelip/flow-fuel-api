package com.devappmobile.flowfuel.devicetoken;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.devicetoken.dto.DeviceTokenRequestDTO;
import com.devappmobile.flowfuel.devicetoken.dto.DeviceTokenResponseDTO;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceTokenServiceTest {

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @Mock
    private AuthorizationHelper authorizationHelper;

    @InjectMocks
    private DeviceTokenService deviceTokenService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
    }

    @Test
    void register_tokenNovo_criaEDevolveDTO() {
        when(deviceTokenRepository.findById("token-1")).thenReturn(Optional.empty());
        when(deviceTokenRepository.save(any(DeviceToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeviceTokenRequestDTO request = new DeviceTokenRequestDTO();
        request.setToken("token-1");
        request.setPlatform(DevicePlatform.ANDROID);

        DeviceTokenResponseDTO response = deviceTokenService.register(user, request);

        assertThat(response.getToken()).isEqualTo("token-1");
        assertThat(response.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
    }

    @Test
    void register_tokenJaExistenteDeOutroUsuario_reatribuiAoUsuarioAtual() {
        User outroUsuario = new User();
        outroUsuario.setId(2L);
        DeviceToken existente = new DeviceToken();
        existente.setToken("token-1");
        existente.setUser(outroUsuario);
        existente.setPlatform(DevicePlatform.ANDROID);

        when(deviceTokenRepository.findById("token-1")).thenReturn(Optional.of(existente));
        when(deviceTokenRepository.save(any(DeviceToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeviceTokenRequestDTO request = new DeviceTokenRequestDTO();
        request.setToken("token-1");
        request.setPlatform(DevicePlatform.ANDROID);

        deviceTokenService.register(user, request);

        verify(deviceTokenRepository).save(argThatUserIs(user));
    }

    @Test
    void remove_donoCorreto_removeToken() {
        DeviceToken deviceToken = new DeviceToken();
        deviceToken.setToken("token-1");
        deviceToken.setUser(user);
        when(deviceTokenRepository.findById("token-1")).thenReturn(Optional.of(deviceToken));

        deviceTokenService.remove(user, "token-1");

        verify(deviceTokenRepository).delete(deviceToken);
    }

    @Test
    void remove_tokenInexistente_naoLancaExcecaoENaoChamaDelete() {
        when(deviceTokenRepository.findById("inexistente")).thenReturn(Optional.empty());

        deviceTokenService.remove(user, "inexistente");

        verify(deviceTokenRepository, never()).delete(any());
    }

    @Test
    void remove_donoDiferente_lancaForbiddenOperationException() {
        User outroUsuario = new User();
        outroUsuario.setId(2L);
        DeviceToken deviceToken = new DeviceToken();
        deviceToken.setToken("token-1");
        deviceToken.setUser(outroUsuario);
        when(deviceTokenRepository.findById("token-1")).thenReturn(Optional.of(deviceToken));
        org.mockito.Mockito.doThrow(new ForbiddenOperationException("Token de dispositivo não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsDeviceToken(user, deviceToken);

        assertThatThrownBy(() -> deviceTokenService.remove(user, "token-1"))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(deviceTokenRepository, never()).delete(any());
    }

    private DeviceToken argThatUserIs(User expectedUser) {
        return org.mockito.ArgumentMatchers.argThat(deviceToken -> deviceToken.getUser().equals(expectedUser));
    }
}
