# B2 — Remove Dead Repository Methods & Legacy Overloads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove unused repository methods (`existsById` redeclarations, `existsByLicensePlateAndUserId`, `findByVehicleIdAndOdometerBetween`) and the legacy `uploadProfilePicture` overloads in `UserService`, with all callers migrated to `uploadProfilePictureResponse`.

**Architecture:** Pure deletion task — no new abstractions. Each removal is verified unused via `grep` before deletion, then validated by running the full build/test suite. The `UserService` overloads still have test callers (`UserServiceTest`), so those tests are migrated to call `uploadProfilePictureResponse` directly before the overloads are deleted. Note: roadmap doc B2 lists a dependency on M2 (user service split) for the overload removal, but M2 is still `pending` — this plan removes the overloads directly since the only blocker (test callers) is handled here, so B2 no longer needs to wait on M2.

**Tech Stack:** Java 17, Spring Data JPA, JUnit 5, Mockito, Maven.

---

### Task 1: Remove `VehicleRepository.existsById(Long)`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleRepository.java:24`

- [x] **Step 1: Confirm no callers reference the redundant declaration**

Run: `grep -rn "existsById" src/main/java/com/devappmobile/flowfuel/vehicle/ src/test/java/com/devappmobile/flowfuel/vehicle/`
Expected: only the declaration at `VehicleRepository.java:24` — no call sites in services/tests (the declaration just re-exposes `JpaRepository.existsById`, already confirmed via earlier grep).

- [x] **Step 2: Delete the method**

In `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleRepository.java`, remove line 24:

```java
    boolean existsById(Long id);
```

The file should end with the `findByUserIdAndIsActiveTrue` method and the closing brace, with no blank line left dangling before `}`.

- [x] **Step 3: Compile to verify no breakage**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicle/VehicleRepository.java
git commit -m "chore(vehicle): remove redundant existsById override"
```

---

### Task 2: Remove `RefuelRepository.existsById(Long)`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java:34`

- [x] **Step 1: Confirm no callers reference the redundant declaration**

Run: `grep -rn "existsById" src/main/java/com/devappmobile/flowfuel/refuel/ src/test/java/com/devappmobile/flowfuel/refuel/`
Expected: only the declaration at `RefuelRepository.java:34`.

- [x] **Step 2: Delete the method**

In `src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java`, remove line 34 (and the blank line either immediately before or after it, keeping single blank-line separation consistent with the rest of the file):

```java
    boolean existsById(Long id);
```

- [x] **Step 3: Compile to verify no breakage**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java
git commit -m "chore(refuel): remove redundant existsById override"
```

---

### Task 3: Remove `VehicleRepository.existsByLicensePlateAndUserId`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleRepository.java:18-20`

- [x] **Step 1: Confirm no callers anywhere in the project**

Run: `grep -rn "existsByLicensePlateAndUserId" src/main src/test`
Expected: only the declaration in `VehicleRepository.java` — no usages in services, controllers, or tests.

- [x] **Step 2: Delete the method and its `@Query` annotation**

In `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleRepository.java`, remove lines 18-20:

```java
    @Query("SELECT COUNT(v) > 0 FROM Vehicle v WHERE v.licensePlate = :licensePlate AND v.user.id = :userId")
    boolean existsByLicensePlateAndUserId(@Param("licensePlate") String licensePlate, 
                                         @Param("userId") Long userId);
```

After this and Task 1's removal, check whether the `Query` and `Param` imports are still used elsewhere in the file — they are not (no other `@Query`-annotated methods remain in `VehicleRepository`), so also remove these two import lines:

```java
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
```

The resulting file should look like:

```java
package com.devappmobile.flowfuel.vehicle;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findByUserId(Long userId);

    Page<Vehicle> findByUserId(Long userId, Pageable pageable);

    List<Vehicle> findByUserIdAndIsActiveTrue(Long userId);
}
```

- [x] **Step 3: Compile to verify no breakage**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicle/VehicleRepository.java
git commit -m "chore(vehicle): remove unused existsByLicensePlateAndUserId query"
```

---

### Task 4: Remove `RefuelRepository.findByVehicleIdAndOdometerBetween`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java:40-44`

- [x] **Step 1: Confirm no callers anywhere in the project**

Run: `grep -rn "findByVehicleIdAndOdometerBetween" src/main src/test`
Expected: only the declaration in `RefuelRepository.java`.

- [x] **Step 2: Delete the method and its `@Query` annotation**

In `src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java`, remove lines 40-44:

```java
    @Query("SELECT r FROM Refuel r WHERE r.vehicle.id = :vehicleId AND r.odometer BETWEEN :startOdometer AND :endOdometer ORDER BY r.odometer DESC")
    List<Refuel> findByVehicleIdAndOdometerBetween(
            @Param("vehicleId") Long vehicleId,
            @Param("startOdometer") Integer startOdometer,
            @Param("endOdometer") Integer endOdometer);
```

`RefuelRepository` has many other `@Query` methods, so keep the `Query` and `Param` imports.

- [x] **Step 3: Compile and run repository/refuel tests to verify no breakage**

Run: `mvn -q compile && mvn -q test -Dtest=RefuelServiceTest,RefuelControllerIntegrationTest`
Expected: BUILD SUCCESS, all tests pass.

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java
git commit -m "chore(refuel): remove unused findByVehicleIdAndOdometerBetween query"
```

---

### Task 5: Migrate `UserServiceTest` off the legacy `uploadProfilePicture` overloads

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java:178-207`

The three tests below currently call `userService.uploadProfilePicture(Long, MultipartFile)` (the 2-arg legacy overload). They must be migrated to call `uploadProfilePictureResponse(Long, MultipartFile)` before the overloads can be deleted in Task 6.

- [x] **Step 1: Rewrite the three tests to call `uploadProfilePictureResponse`**

Replace lines 178-207 (the `upload_comTipoInvalido_lancaBusinessRule`, `upload_comArquivoMaiorQue5MB_lancaBusinessRule`, and `upload_comImagemValida_atualizaPath` tests) with:

```java
    @Test
    void upload_comTipoInvalido_lancaBusinessRule() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.gif", "image/gif", new byte[100]);

        assertThatThrownBy(() -> userService.uploadProfilePictureResponse(1L, file))
                .isInstanceOf(BusinessRuleException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void upload_comArquivoMaiorQue5MB_lancaBusinessRule() {
        byte[] bigFile = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", bigFile);

        assertThatThrownBy(() -> userService.uploadProfilePictureResponse(1L, file))
                .isInstanceOf(BusinessRuleException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void upload_comImagemValida_atualizaPath() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any())).thenReturn(existingUser);

        UploadResponse response = userService.uploadProfilePictureResponse(1L, file);

        assertThat(response).isNotNull();
        assertThat(existingUser.getProfilePicture()).isEqualTo("profile_pictures/1_foto.jpg");
    }
```

Note: `uploadProfilePictureResponse` calls `storageService.getUrl(key)` internally and swallows exceptions (see `UserService.java:189-192`), so the first two tests don't need a `storageService` stub — the method throws `BusinessRuleException` before reaching that call. The third test doesn't stub `storageService.getUrl(...)`, so Mockito's default (returns `null`) applies, matching the existing `uploadProfilePictureResponse_comImagemValida_retornaUrls` test pattern below it.

- [x] **Step 2: Run the test file to verify it passes**

Run: `mvn -q test -Dtest=UserServiceTest`
Expected: BUILD SUCCESS, all tests in `UserServiceTest` pass (including the 3 just migrated and the pre-existing `uploadProfilePictureResponse_comImagemValida_retornaUrls`).

- [x] **Step 3: Confirm no other test or main-code caller of the legacy overloads remains**

Run: `grep -rn "uploadProfilePicture(" src/main src/test | grep -v uploadProfilePictureResponse`
Expected: no remaining calls to `uploadProfilePicture(Long, MultipartFile)` or `uploadProfilePicture(Long, MultipartFile, boolean)` outside of `UserService.java` itself (the two declarations to be deleted in Task 6).

- [x] **Step 4: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java
git commit -m "test(user): migrate uploadProfilePicture tests to uploadProfilePictureResponse"
```

---

### Task 6: Remove the legacy `uploadProfilePicture` overloads from `UserService`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserService.java:197-206`

- [x] **Step 1: Re-confirm no remaining callers**

Run: `grep -rn "uploadProfilePicture(" src/main src/test | grep -v uploadProfilePictureResponse`
Expected: only the two declarations in `UserService.java` (lines 198 and 204) — no call sites.

- [x] **Step 2: Delete both overloads**

In `src/main/java/com/devappmobile/flowfuel/user/UserService.java`, remove lines 197-206:

```java
    // Backwards-compatible overload used by existing tests and callers
    public String uploadProfilePicture(Long userId, MultipartFile file, boolean legacy) {
        uploadProfilePictureResponse(userId, file);
        return "Foto atualizada com sucesso";
    }

    // Keep original signature for tests (convenience)
    public String uploadProfilePicture(Long userId, MultipartFile file) {
        return uploadProfilePicture(userId, file, true);
    }
```

`uploadProfilePictureResponse` (lines 152-195) and `deleteUser` (line 208 onward) must remain untouched, with a single blank line separating them.

- [x] **Step 3: Compile to verify no breakage**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/UserService.java
git commit -m "chore(user): remove legacy uploadProfilePicture overloads"
```

---

### Task 7: Full regression run

**Files:** none (verification only)

- [x] **Step 1: Run the full test suite**

Run: `mvn test`
Expected: BUILD SUCCESS, 0 failures, 0 errors.

- [x] **Step 2: Run a full compile to catch any stray reference**

Run: `mvn compile`
Expected: BUILD SUCCESS.

- [x] **Step 3: Re-run the four target greps to confirm removal**

Run:
```bash
grep -rn "boolean existsById(Long id)" src/main/java/com/devappmobile/flowfuel/vehicle/ src/main/java/com/devappmobile/flowfuel/refuel/
grep -rn "existsByLicensePlateAndUserId" src/main src/test
grep -rn "findByVehicleIdAndOdometerBetween" src/main src/test
grep -rn "uploadProfilePicture(Long" src/main src/test
```
Expected: no matches for any of the four.

---

### Task 8: Update roadmap documentation

**Files:**
- Modify: `docs/roadmap/phase-4/B2-remove-dead-code.md`

- [x] **Step 1: Mark the checklist items and flip status to done**

In `docs/roadmap/phase-4/B2-remove-dead-code.md`:
- Change the frontmatter `status: pending` to `status: done`.
- Check every box in the `## Checklist` section (`- [ ]` → `- [x]`) for: Analisar código atual, Confirmar (grep) que métodos candidatos não são usados, Implementar solução (remoção), Atualizar documentação, Executar testes de regressão, Abrir PR (check this last one only after the PR is actually opened in Task 9).

- [x] **Step 2: Commit**

```bash
git add docs/roadmap/phase-4/B2-remove-dead-code.md
git commit -m "docs(roadmap): mark B2 remove dead code as done"
```

---

### Task 9: Open PR

**Files:** none

- [ ] **Step 1: Push the branch and open a PR**

Follow the repository's standard PR flow (see `superpowers:finishing-a-development-branch`) once all prior tasks are committed and `mvn test` is green on the branch.
