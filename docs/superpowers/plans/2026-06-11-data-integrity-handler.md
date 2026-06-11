# A2 — DataIntegrityViolationException Handler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `DataIntegrityViolationException` (thrown when a DB `UNIQUE` constraint is violated, e.g. the TOCTOU race in `UserService.register`/`updateUserProfile`) return `409 Conflict` with `ErrorCode.CONFLICT` instead of falling through to the generic `500 Internal Server Error` handler.

**Architecture:** Add a new `@ExceptionHandler(DataIntegrityViolationException.class)` method to the existing `GlobalExceptionHandler` (`@RestControllerAdvice`), reusing the existing `build`/`logClientError`/`problemDetail` helpers. No new `ErrorCode` is needed — `CONFLICT` already exists. Coverage comes from a unit test on the new handler method plus two new `UserServiceTest` cases proving the exception propagates from `UserService` instead of being swallowed.

**Tech Stack:** Spring Boot (Spring MVC `@RestControllerAdvice`, `ProblemDetail`/RFC 7807), JUnit 5, Mockito, AssertJ, Maven (`mvn test`).

---

## File Structure

- Modify: `src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java` — add the new exception handler method.
- Create: `src/test/java/com/devappmobile/flowfuel/config/GlobalExceptionHandlerTest.java` — unit test for the new handler method.
- Modify: `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java` — add two regression tests proving `register`/`updateUserProfile` propagate `DataIntegrityViolationException`.

No changes needed to `ErrorCode.java` (`CONFLICT` already exists) or to `UserService.java` (it doesn't catch the exception today, so it already propagates — the new tests document/lock in this behavior).

---

### Task 1: Add `GlobalExceptionHandlerTest` and the new handler (TDD)

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/config/GlobalExceptionHandlerTest.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/devappmobile/flowfuel/config/GlobalExceptionHandlerTest.java`:

```java
package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    @Test
    void handleDataIntegrityViolation_retorna409ComCodeConflict() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-123");
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_email_key\"");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/v1/auth/register");

        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrityViolation(ex, req);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).isNotNull();
        assertThat(body.getProperties().get("code")).isEqualTo("CONFLICT");
        assertThat(body.getProperties().get("requestId")).isEqualTo("req-123");
        assertThat(body.getInstance()).hasToString("/api/v1/auth/register");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=GlobalExceptionHandlerTest test`
Expected: compilation error or test failure — `handleDataIntegrityViolation` does not exist yet on `GlobalExceptionHandler`.

- [ ] **Step 3: Implement the handler**

In `src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java`:

1. Add the import (alphabetical order, after the `jakarta.validation.ConstraintViolationException` import):

```java
import org.springframework.dao.DataIntegrityViolationException;
```

2. Add the new handler method right before `handleGeneric` (after `handleHttpMessageNotReadable`, around line 93):

```java
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex,
            HttpServletRequest req) {
        logClientError(ErrorCode.CONFLICT, req, "Constraint de unicidade violada");
        return build(ErrorCode.CONFLICT, "Recurso já existe ou viola uma restrição única", req.getRequestURI());
    }

```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -Dtest=GlobalExceptionHandlerTest test`
Expected: PASS (1 test, 0 failures)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java src/test/java/com/devappmobile/flowfuel/config/GlobalExceptionHandlerTest.java
git commit -m "feat: handle DataIntegrityViolationException as 409 Conflict"
```

---

### Task 2: Add regression tests in `UserServiceTest`

These tests document that `UserService.register`/`updateUserProfile` do not catch `DataIntegrityViolationException` — it propagates up to `GlobalExceptionHandler`. No production code changes are expected here; both tests should pass immediately, locking in the current (correct) behavior.

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java`

- [ ] **Step 1: Write the new tests**

Add the import (after the `com.devappmobile.flowfuel.storage.StorageService` import, around line 9):

```java
import org.springframework.dao.DataIntegrityViolationException;
```

Add two new test methods at the end of the `// --- register ---` section, right after `register_comEmailDuplicado_lancaConflictSemSalvar` (around line 107):

```java

    @Test
    void register_quandoSaveLancaDataIntegrityViolation_propagaExcecao() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setEmail("race@example.com");
        dto.setPassword("senha123");

        when(userRepository.findByEmail("race@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("senha123")).thenReturn("hashed");
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_email_key\""));

        assertThatThrownBy(() -> userService.register(dto))
                .isInstanceOf(DataIntegrityViolationException.class);
        verifyNoInteractions(accountActivationService);
    }
```

Add one new test method at the end of the class, right before the final closing `}` (around line 291, after `deleteUser_inexistente_lancaResourceNotFound`). Add a new section comment too:

```java

    // --- updateUserProfile ---

    @Test
    void updateUserProfile_quandoSaveLancaDataIntegrityViolation_propagaExcecao() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.findByEmail("outro@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_email_key\""));

        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setEmail("outro@example.com");

        assertThatThrownBy(() -> userService.updateUserProfile(1L, dto))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
```

- [ ] **Step 2: Run the tests to verify they pass**

Run: `mvn -q -Dtest=UserServiceTest test`
Expected: PASS (all `UserServiceTest` tests, including the 2 new ones, 0 failures)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java
git commit -m "test: lock in DataIntegrityViolationException propagation from UserService"
```

---

### Task 3: Full regression run

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests pass (including `UserControllerIntegrationTest#register_comEmailDuplicado_retorna409`, `GlobalExceptionHandlerTest`, and `UserServiceTest`).

- [ ] **Step 2: If everything passes, no further action — Tasks 1 and 2 commits already cover the change set.**
