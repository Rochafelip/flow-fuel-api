package com.devappmobile.flowfuel.devicetoken;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.devicetoken.dto.DeviceTokenRequestDTO;
import com.devappmobile.flowfuel.devicetoken.dto.DeviceTokenResponseDTO;
import com.devappmobile.flowfuel.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final AuthorizationHelper authorizationHelper;

    public DeviceTokenResponseDTO register(User user, DeviceTokenRequestDTO request) {
        DeviceToken deviceToken = deviceTokenRepository.findById(request.getToken())
                .orElseGet(DeviceToken::new);
        deviceToken.setToken(request.getToken());
        deviceToken.setUser(user);
        deviceToken.setPlatform(request.getPlatform());
        deviceTokenRepository.save(deviceToken);
        return new DeviceTokenResponseDTO(deviceToken.getToken(), deviceToken.getPlatform());
    }

    public void remove(User user, String token) {
        Optional<DeviceToken> deviceToken = deviceTokenRepository.findById(token);
        if (deviceToken.isEmpty()) {
            return;
        }
        authorizationHelper.ensureOwnsDeviceToken(user, deviceToken.get());
        deviceTokenRepository.delete(deviceToken.get());
    }
}
