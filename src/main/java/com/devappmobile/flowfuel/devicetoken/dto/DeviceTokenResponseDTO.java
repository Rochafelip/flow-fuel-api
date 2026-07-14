package com.devappmobile.flowfuel.devicetoken.dto;

import com.devappmobile.flowfuel.devicetoken.DevicePlatform;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class DeviceTokenResponseDTO {

    private final String token;
    private final DevicePlatform platform;
}
