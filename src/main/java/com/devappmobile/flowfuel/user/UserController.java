package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final PasswordResetService passwordResetService;
    private final com.devappmobile.flowfuel.storage.StorageService storageService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody UserRegisterDTO dto) {
        AuthResponse response = userService.register(dto);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + response.accessToken());
        return ResponseEntity.ok().headers(headers).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenPairResponse> login(@RequestBody LoginRequest loginRequest) {
        TokenPairResponse tokens = userService.login(loginRequest.getEmail(), loginRequest.getPassword());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + tokens.accessToken());
        return ResponseEntity.ok().headers(headers).body(tokens);
    }

    @PostMapping("/refresh")
    public TokenPairResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return userService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        userService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    public ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return passwordResetService.requestReset(request.email());
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.reset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/password")
    public ResponseEntity<Void> changePassword(@PathVariable Long userId,
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        userService.changePassword(userId, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/upload-profile-picture")
    public UploadResponse uploadProfilePicture(@PathVariable Long userId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        return userService.uploadProfilePictureResponse(userId, file);
    }

    @GetMapping("/{userId}/profile-picture")
    public ResponseEntity<byte[]> getProfilePicture(@PathVariable Long userId,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        String key = userService.getProfilePictureKey(userId);
        if (key == null) return ResponseEntity.noContent().build();
        com.devappmobile.flowfuel.storage.StorageService.StorageObject obj = storageService.download(key);
        return ResponseEntity.ok()
                .header("Content-Type", obj.contentType())
                .body(obj.data());
    }

    @DeleteMapping("/{userId}/profile-picture")
    public ResponseEntity<Void> deleteProfilePicture(@PathVariable Long userId,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        userService.removeProfilePicture(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/profile")
    public UserResponseDTO getProfile(@PathVariable Long userId,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        return userService.getUserProfile(userId);
    }

    @PutMapping("/{userId}/profile")
    public UserResponseDTO updateProfile(@PathVariable Long userId,
            @RequestBody UserRegisterDTO userDetails,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        return userService.updateUserProfile(userId, userDetails);
    }

    @DeleteMapping("/{userId}")
    public void deleteUser(@PathVariable Long userId,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        userService.deleteUser(userId);
    }

    private void ensureSelf(User authUser, Long userId) {
        if (authUser == null || !authUser.getId().equals(userId)) {
            throw new ForbiddenOperationException("Você não pode operar sobre outro usuário");
        }
    }

    public static class LoginRequest {
        private String email;
        private String password;

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
