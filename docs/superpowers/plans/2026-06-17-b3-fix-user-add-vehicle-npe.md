# B3 — Fix NPE in User.addVehicle/removeVehicle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Initialize the `vehicles` field in `User.java` so `addVehicle`/`removeVehicle` never throw `NullPointerException` on a freshly-instantiated `User`.

**Architecture:** One-line fix — initialize the `@OneToMany` collection field with `new ArrayList<>()` at declaration. Add a new `UserTest.java` unit test covering `addVehicle`/`removeVehicle` behavior, then run existing User/Vehicle integration tests to confirm Hibernate mapping is unaffected.

**Tech Stack:** Java, Spring Boot, JPA/Hibernate, JUnit 5, AssertJ, Maven (`mvn`)

---

## File Structure

- Modify: `src/main/java/com/devappmobile/flowfuel/user/User.java:15,57` — add `ArrayList` import, initialize `vehicles` field.
- Create: `src/test/java/com/devappmobile/flowfuel/user/UserTest.java` — new unit test for `addVehicle`/`removeVehicle`.

---

### Task 1: Write failing test for addVehicle/removeVehicle NPE

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/user/UserTest.java`

- [x] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.vehicle.Vehicle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void addVehicle_onFreshlyInstantiatedUser_addsVehicleAndSetsBackReference() {
        User user = new User();
        Vehicle vehicle = new Vehicle();

        user.addVehicle(vehicle);

        assertThat(user.getVehicles()).containsExactly(vehicle);
        assertThat(vehicle.getUser()).isSameAs(user);
    }

    @Test
    void removeVehicle_onFreshlyInstantiatedUser_removesVehicleAndClearsBackReference() {
        User user = new User();
        Vehicle vehicle = new Vehicle();
        user.addVehicle(vehicle);

        user.removeVehicle(vehicle);

        assertThat(user.getVehicles()).isEmpty();
        assertThat(vehicle.getUser()).isNull();
    }
}
```

- [x] **Step 2: Run test to verify it fails with NPE**

Run: `mvn -q -Dtest=UserTest test`
Expected: FAIL — `java.lang.NullPointerException` thrown from `User.addVehicle` (`vehicles` is `null`).

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/user/UserTest.java
git commit -m "test(user): add failing test for addVehicle/removeVehicle NPE"
```

---

### Task 2: Initialize the vehicles field

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/User.java:15,57`

- [x] **Step 1: Add the `ArrayList` import**

In `src/main/java/com/devappmobile/flowfuel/user/User.java`, change:

```java
import java.time.LocalDateTime;
import java.util.List;
```

to:

```java
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
```

- [x] **Step 2: Initialize the field**

Change:

```java
    @JsonIgnore
    @OneToMany(mappedBy = "user")
    private List<Vehicle> vehicles;
```

to:

```java
    @JsonIgnore
    @OneToMany(mappedBy = "user")
    private List<Vehicle> vehicles = new ArrayList<>();
```

- [x] **Step 3: Run the new test to verify it passes**

Run: `mvn -q -Dtest=UserTest test`
Expected: PASS — both `UserTest` methods green.

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/User.java
git commit -m "fix(user): initialize vehicles collection to prevent NPE in addVehicle/removeVehicle"
```

---

### Task 3: Run regression tests to confirm JPA mapping is unaffected

**Files:**
- No file changes — verification only.

- [x] **Step 1: Run User and Vehicle integration tests**

Run: `mvn -q -Dtest=UserControllerIntegrationTest,VehicleControllerIntegrationTest,UserServiceTest test`
Expected: PASS — no regressions from eager collection initialization (Hibernate replaces the field value with its own managed collection on load, so the initializer only matters for transient/new instances).

- [x] **Step 2: Run full test suite**

Run: `mvn -q test`
Expected: PASS — all existing tests green, no side effects elsewhere.

---

## Self-Review Notes

- Spec coverage: field initialization (Task 2), new unit test covering both `addVehicle` and `removeVehicle` (Task 1), regression run against `UserControllerIntegrationTest`/`VehicleControllerIntegrationTest` (Task 3) — matches spec's "Estratégia de Testes" and "Critérios de Aceitação" sections.
- No placeholders: all code blocks are complete and runnable.
- Type consistency: `User.getVehicles()`/`Vehicle.getUser()` rely on Lombok `@Getter`/`@Setter` already present on both entities — verified in `User.java` and assumed consistent on `Vehicle.java` per existing `@Setter` usage (`vehicle.setUser(this)` already compiles in current code).
