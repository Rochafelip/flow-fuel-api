# B6 — AuthorizationHelper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extrair a lógica duplicada de checagem de propriedade (`ownsVehicle`/`ownsRefuel`/`ownsEvent`) de 4 services para um `@Component AuthorizationHelper` centralizado.

**Architecture:** `AuthorizationHelper` expõe métodos `ensureOwns*(User, Resource)` que lançam `ForbiddenOperationException` diretamente — mesmo padrão de `ensureSelf()` já existente em `UserController`. Cada service injeta o helper via construtor (`@RequiredArgsConstructor`) e remove seus métodos privados de ownership.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, AssertJ

---

## File Map

| Ação | Arquivo |
|------|---------|
| **Criar** | `src/main/java/com/devappmobile/flowfuel/common/AuthorizationHelper.java` |
| **Criar** | `src/test/java/com/devappmobile/flowfuel/common/AuthorizationHelperTest.java` |
| **Modificar** | `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java` |
| **Modificar** | `src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java` |
| **Modificar** | `src/main/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventService.java` |
| **Modificar** | `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java` |
| **Modificar** | `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java` |
| **Modificar** | `src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTest.java` |
| **Modificar** | `src/test/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventServiceTest.java` |
| **Modificar** | `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java` |

---

## Task 1: AuthorizationHelperTest (failing)

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/common/AuthorizationHelperTest.java`

- [ ] **Step 1.1: Escrever o teste**

```java
package com.devappmobile.flowfuel.common;

import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationHelperTest {

    private AuthorizationHelper helper;
    private User owner;
    private User other;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        helper = new AuthorizationHelper();

        owner = new User("owner@test.com", "hash", "Owner");
        owner.setId(1L);

        other = new User("other@test.com", "hash", "Other");
        other.setId(2L);

        vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setUser(owner);
    }

    // --- ensureOwnsVehicle ---

    @Test
    void ensureOwnsVehicle_owner_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> helper.ensureOwnsVehicle(owner, vehicle));
    }

    @Test
    void ensureOwnsVehicle_notOwner_throwsForbidden() {
        assertThatThrownBy(() -> helper.ensureOwnsVehicle(other, vehicle))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Veículo não pertence ao usuário");
    }

    // --- ensureOwnsRefuel ---

    @Test
    void ensureOwnsRefuel_owner_doesNotThrow() {
        Refuel refuel = new Refuel();
        refuel.setVehicle(vehicle);

        assertThatNoException().isThrownBy(() -> helper.ensureOwnsRefuel(owner, refuel));
    }

    @Test
    void ensureOwnsRefuel_notOwner_throwsForbidden() {
        Refuel refuel = new Refuel();
        refuel.setVehicle(vehicle);

        assertThatThrownBy(() -> helper.ensureOwnsRefuel(other, refuel))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Abastecimento não pertence ao usuário");
    }

    // --- ensureOwnsEvent ---

    @Test
    void ensureOwnsEvent_owner_doesNotThrow() {
        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);

        assertThatNoException().isThrownBy(() -> helper.ensureOwnsEvent(owner, event));
    }

    @Test
    void ensureOwnsEvent_notOwner_throwsForbidden() {
        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);

        assertThatThrownBy(() -> helper.ensureOwnsEvent(other, event))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Evento não pertence ao usuário");
    }
}
```

- [ ] **Step 1.2: Rodar para confirmar falha (classe não existe)**

```bash
./mvnw test -pl . -Dtest=AuthorizationHelperTest -q 2>&1 | tail -10
```

Esperado: `COMPILATION ERROR` — `AuthorizationHelper` não encontrado.

- [ ] **Step 1.3: Commit do teste**

```bash
git add src/test/java/com/devappmobile/flowfuel/common/AuthorizationHelperTest.java
git commit -m "test(auth): add failing AuthorizationHelperTest for B6"
```

---

## Task 2: Implementar AuthorizationHelper

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/common/AuthorizationHelper.java`

- [ ] **Step 2.1: Criar a classe**

```java
package com.devappmobile.flowfuel.common;

import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationHelper {

    public void ensureOwnsVehicle(User user, Vehicle vehicle) {
        if (!vehicle.getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Veículo não pertence ao usuário");
        }
    }

    public void ensureOwnsRefuel(User user, Refuel refuel) {
        if (!refuel.getVehicle().getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Abastecimento não pertence ao usuário");
        }
    }

    public void ensureOwnsEvent(User user, VehicleEvent event) {
        if (!event.getVehicle().getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Evento não pertence ao usuário");
        }
    }
}
```

- [ ] **Step 2.2: Rodar o teste unitário do helper**

```bash
./mvnw test -pl . -Dtest=AuthorizationHelperTest -q 2>&1 | tail -10
```

Esperado: `BUILD SUCCESS` — 6 testes passando.

- [ ] **Step 2.3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/common/AuthorizationHelper.java
git commit -m "feat(auth): add AuthorizationHelper component (B6)"
```

---

## Task 3: Refatorar VehicleService

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java`

O `VehicleService` usa `findOwned(user, id)` — um método privado que busca o veículo e verifica ownership. Vamos manter o `findOwned` mas delegar a checagem ao `AuthorizationHelper`.

- [ ] **Step 3.1: Adicionar `AuthorizationHelper` ao `VehicleService`**

Substituir o campo existente e o método `findOwned`:

```java
// Adicionar import no topo
import com.devappmobile.flowfuel.common.AuthorizationHelper;

// Adicionar campo (junto com os existentes via @RequiredArgsConstructor)
private final AuthorizationHelper authorizationHelper;

// Substituir o método findOwned:
private Vehicle findOwned(User user, Long id) {
    Vehicle vehicle = vehicleRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Veículo", id));
    authorizationHelper.ensureOwnsVehicle(user, vehicle);
    return vehicle;
}
```

Remover o import de `ForbiddenOperationException` se ele só era usado em `findOwned` (verificar antes de remover).

- [ ] **Step 3.2: Adicionar mock de `AuthorizationHelper` no `VehicleServiceTest`**

```java
// Adicionar import
import com.devappmobile.flowfuel.common.AuthorizationHelper;

// Adicionar mock (junto com os existentes)
@Mock private AuthorizationHelper authorizationHelper;
```

Para os testes que verificam o lançamento de `ForbiddenOperationException` no `findOwned`, adicionar stub:

```java
// Exemplo de stub para simular acesso negado:
doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
        .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);
```

Procurar todos os testes em `VehicleServiceTest` que esperam `ForbiddenOperationException` e adicionar o stub correspondente no `when()` do mock de `vehicleRepository` já existente — o mock do helper precisa ser configurado antes da chamada ao service.

> **Dica:** rode `./mvnw test -Dtest=VehicleServiceTest` depois de cada mudança para ver quais testes quebram.

- [ ] **Step 3.3: Rodar os testes do VehicleService**

```bash
./mvnw test -pl . -Dtest=VehicleServiceTest -q 2>&1 | tail -15
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 3.4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java \
        src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java
git commit -m "refactor(vehicle): delegate ownership check to AuthorizationHelper (B6)"
```

---

## Task 4: Refatorar RefuelService

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTest.java`

O `RefuelService` tem 5 ocorrências de `if (!ownsVehicle/ownsRefuel...) throw` e 2 métodos privados `ownsVehicle` e `ownsRefuel`.

- [ ] **Step 4.1: Adicionar `AuthorizationHelper` ao `RefuelService`**

```java
// Adicionar import
import com.devappmobile.flowfuel.common.AuthorizationHelper;

// Adicionar campo
private final AuthorizationHelper authorizationHelper;
```

- [ ] **Step 4.2: Substituir ocorrências em `createRefuel`**

Antes:
```java
if (!ownsVehicle(user, vehicle)) {
    throw new ForbiddenOperationException("Veículo não pertence ao usuário");
}
```

Depois:
```java
authorizationHelper.ensureOwnsVehicle(user, vehicle);
```

- [ ] **Step 4.3: Substituir ocorrências em `getVehicleRefuels`**

Antes:
```java
if (!ownsVehicle(user, vehicle)) {
    throw new ForbiddenOperationException("Veículo não pertence ao usuário");
}
```

Depois:
```java
authorizationHelper.ensureOwnsVehicle(user, vehicle);
```

- [ ] **Step 4.4: Substituir ocorrências em `getRefuelById`**

Antes:
```java
if (!ownsRefuel(user, refuel)) {
    throw new ForbiddenOperationException("Abastecimento não pertence ao usuário");
}
```

Depois:
```java
authorizationHelper.ensureOwnsRefuel(user, refuel);
```

- [ ] **Step 4.5: Substituir ocorrências em `updateRefuel`**

Antes:
```java
if (!ownsRefuel(user, refuel)) {
    throw new ForbiddenOperationException("Abastecimento não pertence ao usuário");
}
```

Depois:
```java
authorizationHelper.ensureOwnsRefuel(user, refuel);
```

- [ ] **Step 4.6: Substituir ocorrência em `deleteRefuel`**

Antes:
```java
if (!ownsRefuel(user, refuel)) {
    throw new ForbiddenOperationException("Abastecimento não pertence ao usuário");
}
```

Depois:
```java
authorizationHelper.ensureOwnsRefuel(user, refuel);
```

- [ ] **Step 4.7: Remover métodos privados `ownsVehicle` e `ownsRefuel`**

Apagar do `RefuelService`:
```java
private boolean ownsVehicle(User user, Vehicle vehicle) {
    return vehicle.getUser().getId().equals(user.getId());
}

private boolean ownsRefuel(User user, Refuel refuel) {
    return ownsVehicle(user, refuel.getVehicle());
}
```

Remover o import de `ForbiddenOperationException` se não for mais usado.

- [ ] **Step 4.8: Atualizar `RefuelServiceTest`**

```java
// Adicionar import
import com.devappmobile.flowfuel.common.AuthorizationHelper;

// Adicionar mock
@Mock private AuthorizationHelper authorizationHelper;
```

Para testes que esperam `ForbiddenOperationException`, adicionar stubs:

```java
// Para acesso negado a veículo:
doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
        .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

// Para acesso negado a abastecimento:
doThrow(new ForbiddenOperationException("Abastecimento não pertence ao usuário"))
        .when(authorizationHelper).ensureOwnsRefuel(otherUser, refuel);
```

- [ ] **Step 4.9: Rodar os testes do RefuelService**

```bash
./mvnw test -pl . -Dtest=RefuelServiceTest -q 2>&1 | tail -15
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 4.10: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java \
        src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTest.java
git commit -m "refactor(refuel): delegate ownership check to AuthorizationHelper (B6)"
```

---

## Task 5: Refatorar VehicleEventService

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventServiceTest.java`

O `VehicleEventService` tem 4 ocorrências de `if (!ownsVehicle/ownsEvent...) throw` e 2 métodos privados.

- [ ] **Step 5.1: Adicionar `AuthorizationHelper` ao `VehicleEventService`**

```java
// Adicionar import
import com.devappmobile.flowfuel.common.AuthorizationHelper;

// Adicionar campo
private final AuthorizationHelper authorizationHelper;
```

- [ ] **Step 5.2: Substituir ocorrências em `create`**

Antes:
```java
if (!ownsVehicle(user, vehicle)) {
    throw new ForbiddenOperationException("Veículo não pertence ao usuário");
}
```

Depois:
```java
authorizationHelper.ensureOwnsVehicle(user, vehicle);
```

- [ ] **Step 5.3: Substituir ocorrências em `getById`**

Antes:
```java
if (!ownsEvent(user, event)) {
    throw new ForbiddenOperationException("Evento não pertence ao usuário");
}
```

Depois:
```java
authorizationHelper.ensureOwnsEvent(user, event);
```

- [ ] **Step 5.4: Substituir ocorrências em `update`**

Antes:
```java
if (!ownsEvent(user, event)) {
    throw new ForbiddenOperationException("Evento não pertence ao usuário");
}
```

Depois:
```java
authorizationHelper.ensureOwnsEvent(user, event);
```

- [ ] **Step 5.5: Substituir ocorrências em `delete`**

Antes:
```java
if (!ownsEvent(user, event)) {
    throw new ForbiddenOperationException("Evento não pertence ao usuário");
}
```

Depois:
```java
authorizationHelper.ensureOwnsEvent(user, event);
```

- [ ] **Step 5.6: Substituir ocorrência em `getVehicleEvents`**

Antes:
```java
if (!ownsVehicle(user, vehicle)) {
    throw new ForbiddenOperationException("Veículo não pertence ao usuário");
}
```

Depois:
```java
authorizationHelper.ensureOwnsVehicle(user, vehicle);
```

- [ ] **Step 5.7: Remover métodos privados `ownsVehicle` e `ownsEvent`**

Apagar do `VehicleEventService`:
```java
private boolean ownsVehicle(User user, Vehicle vehicle) {
    return vehicle.getUser().getId().equals(user.getId());
}

private boolean ownsEvent(User user, VehicleEvent event) {
    return ownsVehicle(user, event.getVehicle());
}
```

Remover o import de `ForbiddenOperationException` se não for mais usado.

- [ ] **Step 5.8: Atualizar `VehicleEventServiceTest`**

```java
// Adicionar import
import com.devappmobile.flowfuel.common.AuthorizationHelper;

// Adicionar mock
@Mock private AuthorizationHelper authorizationHelper;
```

Para testes que esperam `ForbiddenOperationException`, adicionar stubs:

```java
// Para acesso negado a veículo:
doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
        .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

// Para acesso negado a evento (onde 'event' é o VehicleEvent do setUp):
doThrow(new ForbiddenOperationException("Evento não pertence ao usuário"))
        .when(authorizationHelper).ensureOwnsEvent(otherUser, event);
```

- [ ] **Step 5.9: Rodar os testes do VehicleEventService**

```bash
./mvnw test -pl . -Dtest=VehicleEventServiceTest -q 2>&1 | tail -15
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 5.10: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventService.java \
        src/test/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventServiceTest.java
git commit -m "refactor(vehicleevent): delegate ownership check to AuthorizationHelper (B6)"
```

---

## Task 6: Refatorar DashboardService

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java`

O `DashboardService` usa a checagem inline (sem método privado auxiliar) em `getVehicleDashboard`.

- [ ] **Step 6.1: Adicionar `AuthorizationHelper` ao `DashboardService`**

```java
// Adicionar import
import com.devappmobile.flowfuel.common.AuthorizationHelper;

// Adicionar campo
private final AuthorizationHelper authorizationHelper;
```

- [ ] **Step 6.2: Substituir checagem inline em `getVehicleDashboard`**

Antes (linhas 31–32):
```java
if (!vehicle.getUser().getId().equals(user.getId())) {
    throw new ForbiddenOperationException("Veículo não pertence ao usuário");
}
```

Depois:
```java
authorizationHelper.ensureOwnsVehicle(user, vehicle);
```

Remover o import de `ForbiddenOperationException` se não for mais usado.

- [ ] **Step 6.3: Atualizar `DashboardServiceTest`**

```java
// Adicionar import
import com.devappmobile.flowfuel.common.AuthorizationHelper;

// Adicionar mock
@Mock private AuthorizationHelper authorizationHelper;
```

Para o teste que espera `ForbiddenOperationException`, adicionar stub:

```java
doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
        .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);
```

- [ ] **Step 6.4: Rodar os testes do DashboardService**

```bash
./mvnw test -pl . -Dtest=DashboardServiceTest -q 2>&1 | tail -15
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 6.5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java \
        src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java
git commit -m "refactor(dashboard): delegate ownership check to AuthorizationHelper (B6)"
```

---

## Task 7: Regressão completa e verificação final

- [ ] **Step 7.1: Rodar toda a suíte de testes**

```bash
./mvnw test -q 2>&1 | tail -20
```

Esperado: `BUILD SUCCESS` — todos os testes passando, incluindo `AuthorizationHelperTest`.

- [ ] **Step 7.2: Verificar ausência de lógica duplicada**

```bash
grep -rn "getUser().getId().equals\|ownsVehicle\|ownsRefuel\|ownsEvent" \
     src/main/java/com/devappmobile/flowfuel \
     --include="*.java"
```

Esperado: **zero ocorrências** nos 4 services (`VehicleService`, `RefuelService`, `VehicleEventService`, `DashboardService`). A única ocorrência deve ser em `AuthorizationHelper.java`.

- [ ] **Step 7.3: Commit final**

```bash
git add -A
git commit -m "chore(b6): complete AuthorizationHelper refactor — all ownership checks centralized"
```
