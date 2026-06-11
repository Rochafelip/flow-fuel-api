# A1 — Transactional Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `RefuelService.createRefuel`, `VehicleService.setActiveVehicle`, and `UserService.changePassword` atomic by adding `@Transactional`, backed by integration tests that prove rollback on partial failure.

**Architecture:** Each task follows red→green TDD: write a `@SpringBootTest` (real H2) that uses `@MockitoSpyBean` to force a `RuntimeException` on the *second* write of a flow, assert that nothing was persisted, watch it fail, then add `@Transactional` to the service method and watch it pass.

**Tech Stack:** Spring Boot 3.5.7, Spring Data JPA, H2 (test), JUnit 5, Mockito (`@MockitoSpyBean` from `org.springframework.test.context.bean.override.mockito`, available since Spring Framework 6.2 — confirmed present in `spring-test-6.2.12.jar`), AssertJ.

---

## Important context for all tasks

- Test datasource config is in `src/test/resources/application.properties` (H2 in-memory, `ddl-auto=create-drop`) — no extra setup needed for `@SpringBootTest`.
- `@MockitoSpyBean` wraps the real Spring-managed bean. Unstubbed method calls delegate to the real implementation (real DB hits); only the methods you explicitly stub with `doThrow`/`doAnswer` are intercepted.
- Do **not** annotate the test classes themselves with `@Transactional` — that would wrap each test in its own transaction and mask the very rollback behavior we're testing.
- `User` has a 3-arg constructor: `new User(email, password, name)`.
- `Vehicle`/`Refuel`/`User`/`RefreshToken` all use `GenerationType.IDENTITY`, so `repository.save()` on a new entity issues an immediate `INSERT` (but it's still rolled back if the surrounding transaction rolls back).

---

## Task 1: `RefuelService.createRefuel` — transactional rollback

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java:25` (method `createRefuel`)
- Create: `src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTransactionalTest.java`

### Step 1: Write the failing integration test

Create `src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTransactionalTest.java`:

```java
package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class RefuelServiceTransactionalTest {

    @Autowired
    private RefuelService refuelService;

    @Autowired
    private RefuelRepository refuelRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private VehicleRepository vehicleRepository;

    private User user;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        refuelRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(new User("refuel-tx@test.com", "hash", "User"));

        Vehicle newVehicle = new Vehicle();
        newVehicle.setType("Carro");
        newVehicle.setEnergyType(EnergyType.COMBUSTION);
        newVehicle.setCurrentKm(50000);
        newVehicle.setCapacity(55);
        newVehicle.setUser(user);
        vehicle = vehicleRepository.save(newVehicle);
    }

    @Test
    void createRefuel_falhaAoSalvarVeiculo_naoPersisteRefuelNemAtualizaOdometro() {
        doThrow(new RuntimeException("falha simulada ao salvar veiculo"))
                .when(vehicleRepository).save(any(Vehicle.class));

        RefuelRequestDTO request = new RefuelRequestDTO();
        request.setVehicleId(vehicle.getId());
        request.setOdometer(50500);
        request.setEnergyAmount(BigDecimal.valueOf(40));
        request.setPricePerUnit(BigDecimal.valueOf(5.89));
        request.setFullTank(true);

        assertThatThrownBy(() -> refuelService.createRefuel(user, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("falha simulada ao salvar veiculo");

        assertThat(refuelRepository.findAll()).isEmpty();
        Vehicle reloaded = vehicleRepository.findById(vehicle.getId()).orElseThrow();
        assertThat(reloaded.getCurrentKm()).isEqualTo(50000);
    }
}
```

### Step 2: Run test to verify it fails

Run: `./mvnw test -Dtest=RefuelServiceTransactionalTest -pl . 2>&1 | tail -40`

Expected: **FAIL** — `assertThat(refuelRepository.findAll()).isEmpty()` fails because, without `@Transactional`, `refuelRepository.save(refuel)` already committed before `vehicleRepository.save(vehicle)` throws.

### Step 3: Add `@Transactional` to `createRefuel`

In `src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java`, add the import and annotate the method:

```java
import org.springframework.transaction.annotation.Transactional;
```

```java
    @Transactional
    public RefuelResponseDTO createRefuel(User user, RefuelRequestDTO request) {
```

(Line 25, just above the existing method signature.)

### Step 4: Run test to verify it passes

Run: `./mvnw test -Dtest=RefuelServiceTransactionalTest -pl . 2>&1 | tail -40`

Expected: **PASS**.

### Step 5: Run existing RefuelService tests to confirm no regression

Run: `./mvnw test -Dtest=RefuelServiceTest,RefuelControllerIntegrationTest -pl . 2>&1 | tail -40`

Expected: **PASS** (all existing tests green, unaffected by `@Transactional`).

### Step 6: Commit

```bash
git add src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTransactionalTest.java
git commit -m "feat: make RefuelService.createRefuel transactional"
```

---

## Task 2: `VehicleService.setActiveVehicle` — transactional rollback

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java:65` (method `setActiveVehicle`)
- Create: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTransactionalTest.java`

### Context: why the test needs `doAnswer`, not just `doThrow`

`setActiveVehicle` currently has only **one** write call (`vehicleRepository.saveAll(...)`), and `saveAll` is itself transactional in isolation — so a plain "throw on `saveAll`" test would pass *even without* `@Transactional` on the service method (nothing would ever be written). To get a real red→green signal, the test simulates "partial success of the batch" by having the stubbed `saveAll` perform one *real, separate* `save()` call (which — without an enclosing `@Transactional` — commits independently) and then throw. With `@Transactional` on `setActiveVehicle`, that nested `save()` joins the same transaction (default `REQUIRED` propagation) and gets rolled back too.

### Step 1: Write the failing integration test

Create `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTransactionalTest.java`:

```java
package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class VehicleServiceTransactionalTest {

    @Autowired
    private VehicleService vehicleService;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private VehicleRepository vehicleRepository;

    private User user;
    private Vehicle activeVehicle;
    private Vehicle inactiveVehicle;

    @BeforeEach
    void setUp() {
        vehicleRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(new User("vehicle-tx@test.com", "hash", "User"));

        activeVehicle = vehicleRepository.save(newVehicle(user, true));
        inactiveVehicle = vehicleRepository.save(newVehicle(user, false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void setActiveVehicle_falhaNoMeioDoSaveAll_naoAlteraNenhumVeiculo() {
        Long activeId = activeVehicle.getId();
        Long inactiveId = inactiveVehicle.getId();

        doAnswer(invocation -> {
            List<Vehicle> vehicles = (List<Vehicle>) invocation.getArgument(0);
            // Simula sucesso parcial do lote: persiste isoladamente a mudanca
            // do veiculo que estava ativo (agora marcado como inativo) antes
            // de falhar — sem @Transactional no service, isso fica commitado.
            vehicles.stream()
                    .filter(v -> v.getId().equals(activeId))
                    .findFirst()
                    .ifPresent(vehicleRepository::save);
            throw new RuntimeException("falha simulada no meio do saveAll");
        }).when(vehicleRepository).saveAll(anyList());

        assertThatThrownBy(() -> vehicleService.setActiveVehicle(user, inactiveId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("falha simulada no meio do saveAll");

        assertThat(vehicleRepository.findById(activeId).orElseThrow().getIsActive()).isTrue();
        assertThat(vehicleRepository.findById(inactiveId).orElseThrow().getIsActive()).isFalse();
    }

    private Vehicle newVehicle(User owner, boolean active) {
        Vehicle vehicle = new Vehicle();
        vehicle.setType("Carro");
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCurrentKm(10000);
        vehicle.setCapacity(50);
        vehicle.setIsActive(active);
        vehicle.setUser(owner);
        return vehicle;
    }
}
```

### Step 2: Run test to verify it fails

Run: `./mvnw test -Dtest=VehicleServiceTransactionalTest -pl . 2>&1 | tail -40`

Expected: **FAIL** — `vehicleRepository.findById(activeId).orElseThrow().getIsActive()` is `false` instead of `true`, because the nested `save()` inside the stub committed independently (no enclosing transaction).

### Step 3: Add `@Transactional` to `setActiveVehicle`

In `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`, add the import and annotate the method:

```java
import org.springframework.transaction.annotation.Transactional;
```

```java
    @Transactional
    public void setActiveVehicle(User user, Long vehicleId) {
```

(Line 65, just above the existing method signature.)

### Step 4: Run test to verify it passes

Run: `./mvnw test -Dtest=VehicleServiceTransactionalTest -pl . 2>&1 | tail -40`

Expected: **PASS**.

### Step 5: Run existing VehicleService tests to confirm no regression

Run: `./mvnw test -Dtest=VehicleServiceTest,VehicleControllerIntegrationTest -pl . 2>&1 | tail -40`

Expected: **PASS**.

### Step 6: Commit

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTransactionalTest.java
git commit -m "feat: make VehicleService.setActiveVehicle transactional"
```

---

## Task 3: `UserService.changePassword` — transactional rollback

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserService.java:82` (method `changePassword`)
- Create: `src/test/java/com/devappmobile/flowfuel/user/UserServiceTransactionalTest.java`

### Step 1: Write the failing integration test

Create `src/test/java/com/devappmobile/flowfuel/user/UserServiceTransactionalTest.java`:

```java
package com.devappmobile.flowfuel.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class UserServiceTransactionalTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private RefreshTokenRepository refreshTokenRepository;

    private User user;
    private RefreshToken refreshToken;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        user = new User("change-password-tx@test.com", passwordEncoder.encode("OldPass123"), "User");
        user = userRepository.save(user);

        refreshToken = new RefreshToken(user, "tokenhash-tx-test", LocalDateTime.now().plusDays(1));
        refreshToken = refreshTokenRepository.save(refreshToken);
    }

    @Test
    void changePassword_falhaAoRevogarTokens_naoAlteraSenhaNemTokens() {
        String oldPasswordHash = user.getPassword();

        doThrow(new RuntimeException("falha simulada ao revogar tokens"))
                .when(refreshTokenRepository).revokeAllActiveByUserId(any(Long.class), any(LocalDateTime.class));

        assertThatThrownBy(() -> userService.changePassword(user.getId(), "OldPass123", "NewPass456"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("falha simulada ao revogar tokens");

        User reloadedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloadedUser.getPassword()).isEqualTo(oldPasswordHash);

        RefreshToken reloadedToken = refreshTokenRepository.findById(refreshToken.getId()).orElseThrow();
        assertThat(reloadedToken.isRevoked()).isFalse();
    }
}
```

### Step 2: Run test to verify it fails

Run: `./mvnw test -Dtest=UserServiceTransactionalTest -pl . 2>&1 | tail -40`

Expected: **FAIL** — `reloadedUser.getPassword()` no longer equals `oldPasswordHash`, because `userRepository.save(user)` already committed (own transaction) before `refreshTokenService.revokeAllForUser` throws.

### Step 3: Add `@Transactional` to `changePassword`

In `src/main/java/com/devappmobile/flowfuel/user/UserService.java`, add the import and annotate the method:

```java
import org.springframework.transaction.annotation.Transactional;
```

```java
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
```

(Line 82, just above the existing method signature, right after the existing javadoc comment block.)

### Step 4: Run test to verify it passes

Run: `./mvnw test -Dtest=UserServiceTransactionalTest -pl . 2>&1 | tail -40`

Expected: **PASS**.

### Step 5: Run existing UserService tests to confirm no regression

Run: `./mvnw test -Dtest=UserServiceTest,UserControllerIntegrationTest -pl . 2>&1 | tail -40`

Expected: **PASS**.

### Step 6: Commit

```bash
git add src/main/java/com/devappmobile/flowfuel/user/UserService.java src/test/java/com/devappmobile/flowfuel/user/UserServiceTransactionalTest.java
git commit -m "feat: make UserService.changePassword transactional"
```

---

## Task 4: Full regression run

**Files:** none (verification only)

### Step 1: Run the full test suite

Run: `./mvnw test 2>&1 | tail -60`

Expected: **PASS** — all tests green, including the 3 new `*TransactionalTest` classes and all pre-existing tests.

### Step 2: Update roadmap doc status

Modify `docs/roadmap/phase-1/A1-transactional-boundaries.md`:
- Change frontmatter `status: pending` to `status: done`.
- Check off all items in the `## Checklist` section (`- [ ]` → `- [x]`).

### Step 3: Commit

```bash
git add docs/roadmap/phase-1/A1-transactional-boundaries.md
git commit -m "docs: mark A1 transactional boundaries as done"
```
