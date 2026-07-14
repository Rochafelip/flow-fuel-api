package com.devappmobile.flowfuel.devicetoken;

import com.devappmobile.flowfuel.devicetoken.dto.DeviceTokenRequestDTO;
import com.devappmobile.flowfuel.devicetoken.dto.DeviceTokenResponseDTO;
import com.devappmobile.flowfuel.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @PostMapping
    public DeviceTokenResponseDTO registerToken(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody DeviceTokenRequestDTO request) {
        return deviceTokenService.register(user, request);
    }

    @DeleteMapping("/{token}")
    public ResponseEntity<Void> deleteToken(
            @AuthenticationPrincipal User user,
            @PathVariable String token) {
        deviceTokenService.remove(user, token);
        return ResponseEntity.noContent().build();
    }
}
