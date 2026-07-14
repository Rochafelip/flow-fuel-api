package com.devappmobile.flowfuel.devicetoken.dto;

import com.devappmobile.flowfuel.devicetoken.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeviceTokenRequestDTO {

    @NotBlank
    private String token;

    @NotNull
    private DevicePlatform platform;
}
