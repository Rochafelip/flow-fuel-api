package com.devappmobile.flowfuel.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody User user) {
        return userService.register(user);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        return userService.login(loginRequest.getEmail(), loginRequest.getPassword());
    }

    @PostMapping("/{userId}/upload-profile-picture")
    public ResponseEntity<?> uploadProfilePicture(@PathVariable Long userId,
            @RequestParam("file") MultipartFile file) {
        return userService.uploadProfilePicture(userId, file);
    }

    @GetMapping("/{userId}/profile")
    public ResponseEntity<User> getProfile(@PathVariable Long userId) {
        return userService.getUserProfile(userId);
    }

    @PutMapping("/{userId}/profile")
    public ResponseEntity<User> updateProfile(@PathVariable Long userId,
            @RequestBody User userDetails) {
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