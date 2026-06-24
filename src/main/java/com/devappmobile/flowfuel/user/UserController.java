package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final UserProfileService userProfileService;
    private final PasswordResetService passwordResetService;
    private final AccountActivationService accountActivationService;
    private final com.devappmobile.flowfuel.storage.StorageService storageService;

    /**
     * Cadastra a conta (status PENDING_ACTIVATION) e dispara o email de ativacao.
     * NAO loga o usuario: nao retorna tokens. O login so funciona apos ativar.
     */
    @PostMapping("/register")
    public ResponseEntity<UserResponseDTO> register(@Valid @RequestBody UserRegisterDTO dto) {
        UserResponseDTO created = authService.register(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/activate")
    public ResponseEntity<TokenPairResponse> activate(@Valid @RequestBody ActivateAccountRequest request) {
        TokenPairResponse tokens = accountActivationService.activate(request.token());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + tokens.accessToken());
        return ResponseEntity.ok().headers(headers).body(tokens);
    }

    @PostMapping("/resend-activation")
    public AccountActivationResponse resendActivation(@Valid @RequestBody ResendActivationRequest request) {
        return accountActivationService.resendActivation(request.email());
    }

    @PostMapping("/login")
    public ResponseEntity<TokenPairResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        TokenPairResponse tokens = authService.login(loginRequest.email(), loginRequest.password());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + tokens.accessToken());
        return ResponseEntity.ok().headers(headers).body(tokens);
    }

    @PostMapping("/refresh")
    public TokenPairResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
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
        authService.changePassword(userId, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/upload-profile-picture")
    public UploadResponse uploadProfilePicture(@PathVariable Long userId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        return userProfileService.uploadProfilePictureResponse(userId, file);
    }

    @GetMapping("/{userId}/profile-picture")
    public ResponseEntity<byte[]> getProfilePicture(@PathVariable Long userId,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        String key = userProfileService.getProfilePictureKey(userId);
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
        userProfileService.removeProfilePicture(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/profile")
    public UserResponseDTO getProfile(@PathVariable Long userId,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        return userProfileService.getUserProfile(userId);
    }

    @PutMapping("/{userId}/profile")
    public UserResponseDTO updateProfile(@PathVariable Long userId,
            @Valid @RequestBody UserUpdateDTO userDetails,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        return userProfileService.updateUserProfile(userId, userDetails);
    }

    @DeleteMapping("/{userId}")
    public void deleteUser(@PathVariable Long userId,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        authService.deleteUser(userId);
    }

    private void ensureSelf(User authUser, Long userId) {
        if (authUser == null || !authUser.getId().equals(userId)) {
            throw new ForbiddenOperationException("Você não pode operar sobre outro usuário");
        }
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
}
