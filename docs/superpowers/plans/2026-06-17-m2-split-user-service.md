# M2 — Split `UserService` into `AuthService` + `UserProfileService` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `UserService` (208 lines, 6 dependencies, 5 responsibilities) into `AuthService` (auth/session/password) and `UserProfileService` (profile/photo), with `UserController` as a thin facade delegating to both, with zero observable API changes.

**Architecture:** Pure refactor ("strangler" pattern) — no new behavior. Each task is a compile-safe, test-green increment: copy methods into the new service class, redirect the controller to the new class, then delete the now-dead code from `UserService`. `UserControllerIntegrationTest` (807 lines, all `/auth` endpoints) must stay green after every task; it is the regression net for this refactor instead of new failing tests.

**Tech Stack:** Spring Boot, Lombok `@RequiredArgsConstructor`, JUnit 5 + Mockito (`@InjectMocks`), AssertJ.

**Pre-flight findings (already verified in the codebase, do not redo):**
- `M1` (`OpaqueTokenGenerator`/`AbstractOpaqueToken`) and `B6` (`AuthorizationHelper`) are already merged — no blocking dependency.
- The "legacy `uploadProfilePicture` overloads" mentioned in the roadmap doc (`B2-remove-dead-code`) **do not exist** in current `UserService.java` — only the canonical `uploadProfilePictureResponse(Long, MultipartFile)` is present. **Skip step 5 / the legacy-overload removal entirely.**
- `B3` (NPE in `User.addVehicle`/`removeVehicle`) is **already fixed**: `User.java:58` has `private List<Vehicle> vehicles = new ArrayList<>();` and `UserTest.java` already covers `addVehicle`/`removeVehicle`. **Skip folding B3 into this PR** — there is nothing left to do there.
- Only `UserController` injects `UserService` (`grep -rln "UserService" src --include=*.java` returns just `UserController.java`), so no other component (e.g. `DevDataSeeder`) needs rewiring.
- `changePassword` does **not** currently have `@Transactional` — preserve that as-is; adding it is out of scope for this refactor.

---

## Task 1: Create `AuthService` with the auth/session/password methods

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/user/AuthService.java`

- [ ] **Step 1: Create the new service class, copying the six auth-related methods verbatim from `UserService`**

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.config.JwtUtil;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AccountActivationService accountActivationService;

    /**
     * Cadastra um novo usuario com status {@link UserStatus#PENDING_ACTIVATION} e
     * dispara o envio do link de ativacao por email. NAO loga o usuario: o login
     * so passa a funcionar apos a confirmacao do email (ver {@link #login}).
     */
    public UserResponseDTO register(UserRegisterDTO dto) {
        if (userRepository.findByEmail(dto.getEmail()).isPresent()) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email já cadastrado");
        }

        User user = new User();
        user.setEmail(dto.getEmail());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setName(dto.getName());
        user.setPhone(dto.getPhone());
        user.setStatus(UserStatus.PENDING_ACTIVATION);

        User saved = userRepository.save(user);
        accountActivationService.sendActivation(saved);
        return UserResponseDTO.from(saved);
    }

    public TokenPairResponse login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .filter(u -> passwordEncoder.matches(password, u.getPassword()))
                .orElseThrow(() -> new BadCredentialsException("Email ou senha inválidos"));

        if (!user.isActive()) {
            throw new AppException(ErrorCode.ACCOUNT_NOT_ACTIVATED,
                    "Conta não ativada. Verifique seu email para ativar a conta.");
        }

        return issueTokenPair(user);
    }

    public TokenPairResponse refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotated = refreshTokenService.rotate(refreshToken);
        String accessToken = jwtUtil.generateToken(rotated.user().getEmail(), rotated.user().getId());
        return new TokenPairResponse(accessToken, rotated.newRefreshToken(),
                jwtUtil.getAccessTokenTtlMs() / 1000);
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    /**
     * Troca a senha do usuario. Apos sucesso, revoga todas as sessoes ativas
     * (refresh tokens) — o usuario precisa logar novamente em todos os dispositivos.
     */
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = findUserOrThrow(userId);

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new BadCredentialsException("Senha atual inválida");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BusinessRuleException("Nova senha deve ser diferente da atual");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(userId);
    }

    public void deleteUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("Usuário", userId);
        }
        userRepository.deleteById(userId);
    }

    private TokenPairResponse issueTokenPair(User user) {
        String accessToken = jwtUtil.generateToken(user.getEmail(), user.getId());
        String refreshToken = refreshTokenService.issue(user);
        return new TokenPairResponse(accessToken, refreshToken,
                jwtUtil.getAccessTokenTtlMs() / 1000);
    }

    private User findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário", userId));
    }
}
```

- [ ] **Step 2: Compile to confirm the new class builds in isolation (it is not wired in yet)**

Run: `mvn -q -DskipTests compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/AuthService.java
git commit -m "refactor(user): add AuthService with auth/session/password methods copied from UserService"
```

---

## Task 2: Create `AuthServiceTest` covering the moved methods

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/user/AuthServiceTest.java`

- [ ] **Step 1: Write the test class, adapting the `register`/`login`/`changePassword`/`deleteUser` cases from `UserServiceTest` to target `AuthService`**

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.config.JwtUtil;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AccountActivationService accountActivationService;

    @InjectMocks private AuthService authService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User("test@example.com", "hashed_password", "Test User");
        existingUser.setId(1L);
    }

    // --- register ---

    @Test
    void register_comEmailNovo_criaContaPendenteEDisparaAtivacao() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setEmail("novo@example.com");
        dto.setPassword("senha123");
        dto.setName("Novo Usuario");

        when(userRepository.findByEmail("novo@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("senha123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(2L);
            return u;
        });

        UserResponseDTO response = authService.register(dto);

        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("novo@example.com");
        assertThat(response.getId()).isEqualTo(2L);
        verify(userRepository).save(argThat(u -> u.getStatus() == UserStatus.PENDING_ACTIVATION));
        verify(accountActivationService).sendActivation(argThat(u -> u.getId().equals(2L)));
        verifyNoInteractions(jwtUtil, refreshTokenService);
    }

    @Test
    void register_senhaEhHasheadaAntesDePersistar() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setEmail("hash@example.com");
        dto.setPassword("senha_plain");

        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(passwordEncoder.encode("senha_plain")).thenReturn("bcrypt_hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(3L);
            return u;
        });

        authService.register(dto);

        verify(passwordEncoder).encode("senha_plain");
        verify(userRepository).save(argThat(u -> u.getPassword().equals("bcrypt_hash")));
    }

    @Test
    void register_comEmailDuplicado_lancaConflictSemSalvar() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setEmail("test@example.com");
        dto.setPassword("senha123");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> authService.register(dto))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    // --- login ---

    @Test
    void login_comCredenciaisValidas_retornaTokenPair() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha123", "hashed_password")).thenReturn(true);
        when(jwtUtil.generateToken("test@example.com", 1L)).thenReturn("jwt-token-gerado");
        when(jwtUtil.getAccessTokenTtlMs()).thenReturn(900_000L);
        when(refreshTokenService.issue(existingUser)).thenReturn("refresh-plain");

        TokenPairResponse response = authService.login("test@example.com", "senha123");

        assertThat(response).isNotNull();
        assertThat(response.accessToken()).isEqualTo("jwt-token-gerado");
        assertThat(response.refreshToken()).isEqualTo("refresh-plain");
        assertThat(response.expiresIn()).isEqualTo(900L);
    }

    @Test
    void login_comEmailInexistente_lancaBadCredentials() {
        when(userRepository.findByEmail("nao@existe.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("nao@existe.com", "qualquer"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_comSenhaErrada_lancaBadCredentials() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha_errada", "hashed_password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("test@example.com", "senha_errada"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_comContaPendente_lancaAccountNotActivated() {
        existingUser.setStatus(UserStatus.PENDING_ACTIVATION);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha123", "hashed_password")).thenReturn(true);

        assertThatThrownBy(() -> authService.login("test@example.com", "senha123"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVATED));
        verifyNoInteractions(refreshTokenService);
    }

    // --- changePassword ---

    @Test
    void changePassword_comSenhaAtualCorreta_atualizaSenhaERevogaRefreshTokens() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha_atual", "hashed_password")).thenReturn(true);
        when(passwordEncoder.matches("senha_nova", "hashed_password")).thenReturn(false);
        when(passwordEncoder.encode("senha_nova")).thenReturn("hash_nova");

        authService.changePassword(1L, "senha_atual", "senha_nova");

        verify(userRepository).save(argThat(u -> u.getPassword().equals("hash_nova")));
        verify(refreshTokenService).revokeAllForUser(1L);
    }

    @Test
    void changePassword_comSenhaAtualErrada_lancaBadCredentialsSemAlterar() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("errada", "hashed_password")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(1L, "errada", "senha_nova"))
                .isInstanceOf(BadCredentialsException.class);
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void changePassword_comNovaSenhaIgualAtual_lancaBusinessRule() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("mesma", "hashed_password")).thenReturn(true);

        assertThatThrownBy(() -> authService.changePassword(1L, "mesma", "mesma"))
                .isInstanceOf(BusinessRuleException.class);
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    // --- deleteUser ---

    @Test
    void deleteUser_existente_deleta() {
        when(userRepository.existsById(1L)).thenReturn(true);

        authService.deleteUser(1L);

        verify(userRepository).deleteById(1L);
    }

    @Test
    void deleteUser_inexistente_lancaResourceNotFound() {
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> authService.deleteUser(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(userRepository, never()).deleteById(any());
    }
}
```

- [ ] **Step 2: Run the new test class**

Run: `mvn -q test -Dtest=AuthServiceTest`
Expected: `BUILD SUCCESS`, 12 tests passing

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/user/AuthServiceTest.java
git commit -m "test(user): add AuthServiceTest mirroring auth-related UserServiceTest cases"
```

---

## Task 3: Redirect `UserController` to `AuthService` for auth/session/password endpoints

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserController.java`

- [ ] **Step 1: Inject `AuthService` alongside `UserService` and redirect the six auth endpoints to it**

In `UserController.java`, add the field (`src/main/java/com/devappmobile/flowfuel/user/UserController.java:20`):

```java
    private final UserService userService;
    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final AccountActivationService accountActivationService;
    private final com.devappmobile.flowfuel.storage.StorageService storageService;
```

Replace each call site:

```java
    @PostMapping("/register")
    public ResponseEntity<UserResponseDTO> register(@Valid @RequestBody UserRegisterDTO dto) {
        UserResponseDTO created = authService.register(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
```

```java
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
```

```java
    @PutMapping("/{userId}/password")
    public ResponseEntity<Void> changePassword(@PathVariable Long userId,
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        authService.changePassword(userId, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
```

```java
    @DeleteMapping("/{userId}")
    public void deleteUser(@PathVariable Long userId,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        authService.deleteUser(userId);
    }
```

- [ ] **Step 2: Run the full regression net for the user package**

Run: `mvn -q test -Dtest=UserServiceTest,AuthServiceTest,UserControllerIntegrationTest`
Expected: `BUILD SUCCESS`, all tests passing (UserService still has its old methods too, so nothing breaks yet)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/UserController.java
git commit -m "refactor(user): delegate auth/session/password endpoints to AuthService"
```

---

## Task 4: Remove the now-dead auth methods from `UserService` and `UserServiceTest`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java`

- [ ] **Step 1: Delete `register`, `login`, `refresh`, `logout`, `changePassword`, `deleteUser`, `issueTokenPair` from `UserService.java`, and drop the now-unused `jwtUtil`/`refreshTokenService`/`accountActivationService` fields and their imports**

`UserService.java` should become:

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import com.devappmobile.flowfuel.storage.StorageService;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp");

    private final UserRepository userRepository;
    private final StorageService storageService;

    public UserResponseDTO getUserProfile(Long userId) {
        User user = findUserOrThrow(userId);
        String profileKey = user.getProfilePicture();
        String internalUrl = profileKey != null ? ("/auth/" + userId + "/profile-picture") : null;
        String signedUrl = null;
        if (profileKey != null) {
            try {
                signedUrl = storageService.getUrl(profileKey);
            } catch (Exception ignored) {
            }
        }

        UserResponseDTO dto = UserResponseDTO.from(user);
        dto.setProfilePicture(internalUrl);
        dto.setProfilePictureUrl(signedUrl);
        return dto;
    }

    public String getProfilePictureKey(Long userId) {
        return findUserOrThrow(userId).getProfilePicture();
    }

    public void removeProfilePicture(Long userId) {
        User user = findUserOrThrow(userId);
        String key = user.getProfilePicture();
        if (key != null) {
            storageService.delete(key);
            user.setProfilePicture(null);
            userRepository.save(user);
        }
    }

    public UserResponseDTO updateUserProfile(Long userId, UserUpdateDTO dto) {
        User user = findUserOrThrow(userId);

        if (dto.name() != null) user.setName(dto.name());
        if (dto.phone() != null) user.setPhone(dto.phone());

        if (dto.email() != null && !dto.email().equals(user.getEmail())) {
            if (userRepository.findByEmail(dto.email()).isPresent()) {
                throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email já cadastrado");
            }
            user.setEmail(dto.email());
        }

        return UserResponseDTO.from(userRepository.save(user));
    }

    public UploadResponse uploadProfilePictureResponse(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Arquivo não informado");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new BusinessRuleException("Tipo de arquivo inválido. Permitido: JPEG, PNG, WEBP");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessRuleException("Arquivo excede o tamanho máximo de 5 MB");
        }

        User user = findUserOrThrow(userId);

        // cleanup previous image if present
        String previousKey = user.getProfilePicture();
        if (previousKey != null) {
            try {
                storageService.delete(previousKey);
            } catch (Exception ignored) {
            }
        }

        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "photo";

        String key = "profile_pictures/" + userId + "_" + originalName;
        storageService.upload(file, key);
        user.setProfilePicture(key);

        userRepository.save(user);

        String internalUrl = "/auth/" + userId + "/profile-picture";
        String signedUrl = null;
        try {
            signedUrl = storageService.getUrl(key);
        } catch (Exception ignored) {
        }

        return new UploadResponse(internalUrl, signedUrl);
    }

    private User findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário", userId));
    }
}
```

- [ ] **Step 2: Delete the corresponding test sections from `UserServiceTest.java`** — remove the `register`, `login`, `changePassword`, `deleteUser` `@Test` methods (lines 49–155, 238–291 in the original file) and the now-unused `@Mock private JwtUtil jwtUtil;`, `@Mock private RefreshTokenService refreshTokenService;`, `@Mock private AccountActivationService accountActivationService;` fields and their imports (`JwtUtil`, `AppException`, `ErrorCode`, `BadCredentialsException`). `UserServiceTest.java` should retain only `getUserProfile`, upload, and `updateUserProfile` tests (these get fully removed in Task 6 when the class is renamed/replaced).

- [ ] **Step 3: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/UserService.java src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java
git commit -m "refactor(user): remove auth/session/password methods from UserService (moved to AuthService)"
```

---

## Task 5: Rename remaining `UserService` to `UserProfileService`

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/user/UserProfileService.java`
- Create: `src/test/java/com/devappmobile/flowfuel/user/UserProfileServiceTest.java`
- Delete: `src/main/java/com/devappmobile/flowfuel/user/UserService.java`
- Delete: `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java`

- [ ] **Step 1: Create `UserProfileService.java` with the same content as the post-Task-4 `UserService.java`, renaming the class**

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import com.devappmobile.flowfuel.storage.StorageService;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp");

    private final UserRepository userRepository;
    private final StorageService storageService;

    public UserResponseDTO getUserProfile(Long userId) {
        User user = findUserOrThrow(userId);
        String profileKey = user.getProfilePicture();
        String internalUrl = profileKey != null ? ("/auth/" + userId + "/profile-picture") : null;
        String signedUrl = null;
        if (profileKey != null) {
            try {
                signedUrl = storageService.getUrl(profileKey);
            } catch (Exception ignored) {
            }
        }

        UserResponseDTO dto = UserResponseDTO.from(user);
        dto.setProfilePicture(internalUrl);
        dto.setProfilePictureUrl(signedUrl);
        return dto;
    }

    public String getProfilePictureKey(Long userId) {
        return findUserOrThrow(userId).getProfilePicture();
    }

    public void removeProfilePicture(Long userId) {
        User user = findUserOrThrow(userId);
        String key = user.getProfilePicture();
        if (key != null) {
            storageService.delete(key);
            user.setProfilePicture(null);
            userRepository.save(user);
        }
    }

    public UserResponseDTO updateUserProfile(Long userId, UserUpdateDTO dto) {
        User user = findUserOrThrow(userId);

        if (dto.name() != null) user.setName(dto.name());
        if (dto.phone() != null) user.setPhone(dto.phone());

        if (dto.email() != null && !dto.email().equals(user.getEmail())) {
            if (userRepository.findByEmail(dto.email()).isPresent()) {
                throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email já cadastrado");
            }
            user.setEmail(dto.email());
        }

        return UserResponseDTO.from(userRepository.save(user));
    }

    public UploadResponse uploadProfilePictureResponse(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Arquivo não informado");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new BusinessRuleException("Tipo de arquivo inválido. Permitido: JPEG, PNG, WEBP");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessRuleException("Arquivo excede o tamanho máximo de 5 MB");
        }

        User user = findUserOrThrow(userId);

        // cleanup previous image if present
        String previousKey = user.getProfilePicture();
        if (previousKey != null) {
            try {
                storageService.delete(previousKey);
            } catch (Exception ignored) {
            }
        }

        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "photo";

        String key = "profile_pictures/" + userId + "_" + originalName;
        storageService.upload(file, key);
        user.setProfilePicture(key);

        userRepository.save(user);

        String internalUrl = "/auth/" + userId + "/profile-picture";
        String signedUrl = null;
        try {
            signedUrl = storageService.getUrl(key);
        } catch (Exception ignored) {
        }

        return new UploadResponse(internalUrl, signedUrl);
    }

    private User findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário", userId));
    }
}
```

- [ ] **Step 2: Create `UserProfileServiceTest.java` with the `getUserProfile`/upload/`updateUserProfile` cases from the original `UserServiceTest`, targeting `UserProfileService`**

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private StorageService storageService;

    @InjectMocks private UserProfileService userProfileService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User("test@example.com", "hashed_password", "Test User");
        existingUser.setId(1L);
    }

    // --- getUserProfile ---

    @Test
    void getUserProfile_usuarioExistente_retornaDto() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));

        UserResponseDTO response = userProfileService.getUserProfile(1L);

        assertThat(response.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void getUserProfile_usuarioInexistente_lancaResourceNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userProfileService.getUserProfile(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getUserProfile_retornaProfilePictureUrl() {
        existingUser.setProfilePicture("profile_pictures/1_foto.jpg");
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(storageService.getUrl("profile_pictures/1_foto.jpg")).thenReturn("https://signed-url.example.com/profile_pictures/1_foto.jpg");

        UserResponseDTO response = userProfileService.getUserProfile(1L);

        assertThat(response.getProfilePicture()).isEqualTo("/auth/1/profile-picture");
        assertThat(response.getProfilePictureUrl()).isEqualTo("https://signed-url.example.com/profile_pictures/1_foto.jpg");
    }

    // --- uploadProfilePictureResponse ---

    @Test
    void upload_comTipoInvalido_lancaBusinessRule() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.gif", "image/gif", new byte[100]);

        assertThatThrownBy(() -> userProfileService.uploadProfilePictureResponse(1L, file))
                .isInstanceOf(BusinessRuleException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void upload_comArquivoMaiorQue5MB_lancaBusinessRule() {
        byte[] bigFile = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", bigFile);

        assertThatThrownBy(() -> userProfileService.uploadProfilePictureResponse(1L, file))
                .isInstanceOf(BusinessRuleException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void upload_comImagemValida_atualizaPath() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any())).thenReturn(existingUser);

        UploadResponse response = userProfileService.uploadProfilePictureResponse(1L, file);

        assertThat(response).isNotNull();
        assertThat(existingUser.getProfilePicture()).isEqualTo("profile_pictures/1_foto.jpg");
    }

    @Test
    void uploadProfilePictureResponse_comImagemValida_retornaUrls() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any())).thenReturn(existingUser);
        when(storageService.getUrl("profile_pictures/1_foto.jpg")).thenReturn("https://signed-url.example.com/profile_pictures/1_foto.jpg");

        UploadResponse response = userProfileService.uploadProfilePictureResponse(1L, file);

        assertThat(response).isNotNull();
        assertThat(response.getInternalUrl()).isEqualTo("/auth/1/profile-picture");
        assertThat(response.getSignedUrl()).isEqualTo("https://signed-url.example.com/profile_pictures/1_foto.jpg");
        assertThat(existingUser.getProfilePicture()).isEqualTo("profile_pictures/1_foto.jpg");
    }

    // --- updateUserProfile ---

    @Test
    void updateUserProfile_comNameEPhone_atualizaSemTocarEmail() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserUpdateDTO dto = new UserUpdateDTO(null, "Novo Nome", "11999990000");
        UserResponseDTO result = userProfileService.updateUserProfile(1L, dto);

        assertThat(result.getName()).isEqualTo("Novo Nome");
        assertThat(result.getEmail()).isEqualTo("test@example.com");
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void updateUserProfile_comEmailNovo_verificaDuplicidadeEAtualiza() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.findByEmail("novo@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserUpdateDTO dto = new UserUpdateDTO("novo@example.com", null, null);
        UserResponseDTO result = userProfileService.updateUserProfile(1L, dto);

        assertThat(result.getEmail()).isEqualTo("novo@example.com");
        verify(userRepository).findByEmail("novo@example.com");
    }

    @Test
    void updateUserProfile_comEmailDuplicado_lancaConflict() {
        User outro = new User("outro@example.com", "hash", "Outro");
        outro.setId(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.findByEmail("outro@example.com")).thenReturn(Optional.of(outro));

        UserUpdateDTO dto = new UserUpdateDTO("outro@example.com", null, null);

        assertThatThrownBy(() -> userProfileService.updateUserProfile(1L, dto))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUserProfile_comTodosCamposNulos_naoAlteraNada() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserUpdateDTO dto = new UserUpdateDTO(null, null, null);
        UserResponseDTO result = userProfileService.updateUserProfile(1L, dto);

        assertThat(result.getEmail()).isEqualTo("test@example.com");
        assertThat(result.getName()).isEqualTo("Test User");
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void updateUserProfile_usuarioInexistente_lancaResourceNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        UserUpdateDTO dto = new UserUpdateDTO(null, "Nome", null);

        assertThatThrownBy(() -> userProfileService.updateUserProfile(99L, dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
```

- [ ] **Step 3: Run the new test class**

Run: `mvn -q test -Dtest=UserProfileServiceTest`
Expected: `BUILD SUCCESS`, 11 tests passing

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/UserProfileService.java src/test/java/com/devappmobile/flowfuel/user/UserProfileServiceTest.java
git commit -m "refactor(user): add UserProfileService as the renamed remainder of UserService"
```

---

## Task 6: Redirect `UserController` to `UserProfileService` and delete the old `UserService`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserController.java`
- Delete: `src/main/java/com/devappmobile/flowfuel/user/UserService.java`
- Delete: `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java`

- [ ] **Step 1: Replace the `UserService` field with `UserProfileService` and update the five profile/photo endpoints**

```java
    private final AuthService authService;
    private final UserProfileService userProfileService;
    private final PasswordResetService passwordResetService;
    private final AccountActivationService accountActivationService;
    private final com.devappmobile.flowfuel.storage.StorageService storageService;
```

```java
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
```

- [ ] **Step 2: Delete the old files**

```bash
rm src/main/java/com/devappmobile/flowfuel/user/UserService.java
rm src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java
```

- [ ] **Step 3: Run the full project test suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS`, no reference to `UserService` remains (confirm with `grep -rn "UserService" src --include=*.java` returning no matches — `UserServiceTest`/`UserProfileService` names won't match the exact token `UserService` followed by a non-word character, but double check manually)

- [ ] **Step 4: Commit**

```bash
git add -A src/main/java/com/devappmobile/flowfuel/user/UserController.java
git add -u src/main/java/com/devappmobile/flowfuel/user/UserService.java src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java
git commit -m "refactor(user): delegate profile/photo endpoints to UserProfileService and remove UserService"
```

---

## Task 7: Full regression run and roadmap doc update

**Files:**
- Modify: `docs/roadmap/phase-4/M2-split-user-service.md`

- [ ] **Step 1: Run the full Maven test suite (not just the `user` package) to catch any cross-package dependency**

Run: `mvn -q test`
Expected: `BUILD SUCCESS`

- [ ] **Step 2: Confirm `UserControllerIntegrationTest` (all `/auth` endpoints) passed as part of the full run**

Run: `mvn -q test -Dtest=UserControllerIntegrationTest`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Update the roadmap doc — flip the checklist and correct the stale B2/B3 assumptions**

In `docs/roadmap/phase-4/M2-split-user-service.md`, change the frontmatter `status: pending` to `status: done`, and update the Checklist section to:

```markdown
## Checklist

- [x] Confirmar conclusão de M1 e B6
- [x] Analisar código atual
- [x] Implementar solução (split em AuthService + UserProfileService)
- [x] Remover overloads legacy de uploadProfilePicture (já não existiam no código atual)
- [x] Corrigir B3 (NPE addVehicle/removeVehicle) — já estava corrigido antes deste PR
- [x] Adicionar/realocar testes
- [x] Atualizar documentação
- [x] Executar testes de regressão (UserControllerIntegrationTest completo)
- [ ] Abrir PR
```

- [ ] **Step 4: Commit**

```bash
git add docs/roadmap/phase-4/M2-split-user-service.md
git commit -m "docs(roadmap): mark M2 done, note B2/B3 were already resolved before this PR"
```

---

## Self-Review Notes

- **Spec coverage:** All M2 "Requisitos Técnicos" and "Critérios de Aceitação" are covered by Tasks 1–6 except the legacy-overload removal and the B3 fold-in, both of which are no-ops because they were already done in prior PRs (verified directly against current file contents, not assumed) — documented in the pre-flight section and Task 7 instead of being silently dropped.
- **Placeholder scan:** every step shows the literal code being written; no "add appropriate tests" placeholders.
- **Type consistency:** `AuthService` and `UserProfileService` method signatures match exactly what `UserController` calls in Tasks 3 and 6 (`register(UserRegisterDTO)`, `login(String, String)`, `refresh(String)`, `logout(String)`, `changePassword(Long, String, String)`, `deleteUser(Long)`, `getUserProfile(Long)`, `getProfilePictureKey(Long)`, `removeProfilePicture(Long)`, `updateUserProfile(Long, UserUpdateDTO)`, `uploadProfilePictureResponse(Long, MultipartFile)`).
