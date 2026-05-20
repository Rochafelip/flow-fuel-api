package com.devappmobile.flowfuel.user;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<UserResponseDTO> register(@Valid @RequestBody UserRegisterDTO dto) {
        return userService.register(dto);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        ResponseEntity<?> response = userService.login(loginRequest.getEmail(), loginRequest.getPassword());

        if (response.getStatusCode().is2xxSuccessful()) {
            LoginResponse loginResponse = (LoginResponse) response.getBody();
            if (loginResponse != null) {
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Bearer " + loginResponse.getToken());
                return ResponseEntity.ok()
                        .headers(headers)
                        .body(loginResponse);
            }
        }

        return response;
    }

    @PostMapping("/{userId}/upload-profile-picture")
    public ResponseEntity<?> uploadProfilePicture(@PathVariable Long userId,
            @RequestParam("file") MultipartFile file) {
        return userService.uploadProfilePicture(userId, file);
    }

    @GetMapping("/{userId}/profile")
    public ResponseEntity<UserResponseDTO> getProfile(@PathVariable Long userId) {
        return userService.getUserProfile(userId);
    }

    @PutMapping("/{userId}/profile")
    public ResponseEntity<UserResponseDTO> updateProfile(@PathVariable Long userId,
            @RequestBody UserRegisterDTO userDetails) {
        return userService.updateUserProfile(userId, userDetails);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable Long userId) {
        return userService.deleteUser(userId);
    }

    // Classe interna para o login request
    public static class LoginRequest {
        private String email;
        private String password;

        // Getters e Setters
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