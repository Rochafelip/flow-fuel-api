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

    @PostMapping("/register")
    public UserResponseDTO register(@Valid @RequestBody UserRegisterDTO dto) {
        return userService.register(dto);
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

    @PutMapping("/{userId}/password")
    public ResponseEntity<Void> changePassword(@PathVariable Long userId,
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        userService.changePassword(userId, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/upload-profile-picture")
    public String uploadProfilePicture(@PathVariable Long userId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        return userService.uploadProfilePicture(userId, file);
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
