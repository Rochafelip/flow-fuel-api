# Compartilhamento de Veículo (Backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deixar o dono de um veículo convidar (por email) outro usuário FlowFuel já cadastrado a lançar eventos de uso diário e atualizar o odômetro naquele veículo por um prazo definido, sem ganhar acesso a mais nada.

**Architecture:** Novo domínio `vehicleshare` (entidade `VehicleShare`, repositório, serviço, controller REST), seguindo exatamente o padrão de domínio isolado já usado em `devicetoken`. Autorização estendida via um novo método em `AuthorizationHelper` (`ensureOwnsOrHasGuestAccess`), reaproveitado nos dois pontos de escrita que o convidado precisa tocar (`VehicleEventService.create`, `VehicleService.updateOdometer`). Expiração é lazy — checada a cada leitura, sem job em background.

**Tech Stack:** Spring Boot / Java 21, JPA/Hibernate, PostgreSQL (prod/staging) + H2 (testes, schema gerado via `ddl-auto=create-drop` a partir das entidades — a migração Flyway só roda em staging/prod), JUnit 5 + Mockito + AssertJ, MockMvc para testes de integração.

## Global Constraints

- Convite só por email (`inviteeEmail`) — o `User` deste backend não tem campo `username`, só `email` (único).
- Categorias de evento permitidas pro convidado: `FUEL`, `CAR_WASH`, `TIRES`, `OTHER` (categorias de uso diário). Fora desse conjunto → 403, mesmo com share ativo.
- Um veículo só pode ter um `VehicleShare` `PENDING` ou `ACTIVE` por vez.
- Expiração é lazy: nenhum scheduler/job marca shares como `EXPIRED`; a checagem é sempre `status == ACTIVE && expiresAt.isAfter(now)`.
- Convidado nunca vê/edita histórico existente, nem dados cadastrais do veículo, nem pode marcar o veículo emprestado como "ativo" no sentido do backend (`isActive` continua escopado ao dono via `findByUserId`).
- Aceite/recusa/revogação não disparam push — só a criação do convite dispara.
- Todo texto de exceção e commit segue o idioma/estilo já usado no repositório (mensagens de erro em português, commits em inglês no padrão `tipo(escopo): descrição`).

---

### Task 1: `VehicleShareStatus` enum + `VehicleShare` entidade + migração

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareStatus.java`
- Create: `src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShare.java`
- Create: `src/main/resources/db/migration/V11__vehicle_shares.sql`
- Test: `src/test/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareTest.java`

**Interfaces:**
- Produces: `VehicleShareStatus` (enum: `PENDING`, `ACTIVE`, `REJECTED`, `REVOKED`, `EXPIRED`); `VehicleShare` (getters/setters: `getId()/setId(Long)`, `getVehicle()/setVehicle(Vehicle)`, `getOwner()/setOwner(User)`, `getGuest()/setGuest(User)`, `getStatus()/setStatus(VehicleShareStatus)`, `getDurationDays()/setDurationDays(Integer)`, `getCreatedAt()`, `getRespondedAt()/setRespondedAt(LocalDateTime)`, `getExpiresAt()/setExpiresAt(LocalDateTime)`, e o método `boolean isCurrentlyActive()`).

- [ ] **Step 1: Escrever o teste de `isCurrentlyActive()`**

```java
package com.devappmobile.flowfuel.vehicleshare;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleShareTest {

    @Test
    void isCurrentlyActive_statusAtivoEDentroDoPrazo_retornaTrue() {
        VehicleShare share = new VehicleShare();
        share.setStatus(VehicleShareStatus.ACTIVE);
        share.setExpiresAt(LocalDateTime.now().plusHours(1));

        assertThat(share.isCurrentlyActive()).isTrue();
    }

    @Test
    void isCurrentlyActive_statusAtivoMasPrazoVencido_retornaFalse() {
        VehicleShare share = new VehicleShare();
        share.setStatus(VehicleShareStatus.ACTIVE);
        share.setExpiresAt(LocalDateTime.now().minusHours(1));

        assertThat(share.isCurrentlyActive()).isFalse();
    }

    @Test
    void isCurrentlyActive_statusPending_retornaFalse() {
        VehicleShare share = new VehicleShare();
        share.setStatus(VehicleShareStatus.PENDING);
        share.setExpiresAt(LocalDateTime.now().plusHours(1));

        assertThat(share.isCurrentlyActive()).isFalse();
    }

    @Test
    void isCurrentlyActive_semExpiresAt_retornaFalse() {
        VehicleShare share = new VehicleShare();
        share.setStatus(VehicleShareStatus.ACTIVE);

        assertThat(share.isCurrentlyActive()).isFalse();
    }
}
```

- [ ] **Step 2: Rodar o teste pra confirmar que falha (classes ainda não existem)**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleShareTest`
Expected: FAIL (erro de compilação — `VehicleShareStatus` e `VehicleShare` não existem)

- [ ] **Step 3: Criar o enum**

```java
package com.devappmobile.flowfuel.vehicleshare;

public enum VehicleShareStatus {
    PENDING,
    ACTIVE,
    REJECTED,
    REVOKED,
    EXPIRED
}
```

- [ ] **Step 4: Criar a entidade**

```java
package com.devappmobile.flowfuel.vehicleshare;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Table(name = "vehicle_shares")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VehicleShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Vehicle vehicle;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User owner;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guest_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User guest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VehicleShareStatus status;

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    public boolean isCurrentlyActive() {
        return status == VehicleShareStatus.ACTIVE
                && expiresAt != null
                && expiresAt.isAfter(LocalDateTime.now());
    }
}
```

- [ ] **Step 5: Criar a migração** (só roda de fato em staging/prod — testes usam `ddl-auto=create-drop` a partir das entidades, ver `src/test/resources/application.properties`)

```sql
CREATE TABLE vehicle_shares (
    id             BIGSERIAL PRIMARY KEY,
    vehicle_id     BIGINT NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
    owner_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    guest_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status         VARCHAR(20) NOT NULL,
    duration_days  INTEGER NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    responded_at   TIMESTAMP,
    expires_at     TIMESTAMP
);

CREATE INDEX idx_vehicle_shares_guest_status ON vehicle_shares(guest_id, status);

-- So permite um share PENDING/ACTIVE por veiculo por vez.
CREATE UNIQUE INDEX idx_vehicle_shares_active_unique ON vehicle_shares(vehicle_id)
    WHERE status IN ('PENDING', 'ACTIVE');
```

- [ ] **Step 6: Rodar o teste pra confirmar que passa**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleShareTest`
Expected: PASS (4 testes)

- [ ] **Step 7: Commit**

```bash
cd ~/Projetos/flowfuel
git add src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareStatus.java \
        src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShare.java \
        src/main/resources/db/migration/V11__vehicle_shares.sql \
        src/test/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareTest.java
git commit -m "feat(vehicleshare): add VehicleShare entity, status enum and migration"
```

---

### Task 2: `VehicleShareRepository`

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareRepository.java`
- Test: `src/test/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareRepositoryTest.java`

**Interfaces:**
- Consumes: `VehicleShare`, `VehicleShareStatus` (Task 1); `User`, `UserRepository` (`com.devappmobile.flowfuel.user`, já existentes); `Vehicle`, `VehicleRepository` (`com.devappmobile.flowfuel.vehicle`, já existentes).
- Produces: `VehicleShareRepository` com `existsByVehicleIdAndStatusIn(Long, List<VehicleShareStatus>)`, `findFirstByVehicleIdAndStatusInOrderByCreatedAtDesc(Long, List<VehicleShareStatus>)`, `findByGuestIdAndStatus(Long, VehicleShareStatus)`, `findByGuestIdAndStatusAndExpiresAtAfter(Long, VehicleShareStatus, LocalDateTime)`, `existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter(Long, Long, VehicleShareStatus, LocalDateTime)`.

- [ ] **Step 1: Criar a interface vazia (só o `JpaRepository` base, sem os métodos derivados ainda)**

```java
package com.devappmobile.flowfuel.vehicleshare;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VehicleShareRepository extends JpaRepository<VehicleShare, Long> {
}
```

- [ ] **Step 2: Escrever o teste de repositório (vai falhar a compilar — os métodos derivados ainda não existem)**

```java
package com.devappmobile.flowfuel.vehicleshare;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class VehicleShareRepositoryTest {

    @Autowired
    private VehicleShareRepository vehicleShareRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    private User criarUsuario(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPassword("hash");
        user.setName("User " + email);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private Vehicle criarVeiculo(User owner) {
        Vehicle vehicle = new Vehicle();
        vehicle.setType("CARRO");
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCurrentKm(1000);
        vehicle.setCapacity(50);
        vehicle.setUser(owner);
        return vehicleRepository.save(vehicle);
    }

    private VehicleShare criarShare(Vehicle vehicle, User owner, User guest, VehicleShareStatus status, LocalDateTime expiresAt) {
        VehicleShare share = new VehicleShare();
        share.setVehicle(vehicle);
        share.setOwner(owner);
        share.setGuest(guest);
        share.setStatus(status);
        share.setDurationDays(1);
        share.setExpiresAt(expiresAt);
        return vehicleShareRepository.save(share);
    }

    @Test
    void existsByVehicleIdAndStatusIn_comSharePendente_retornaTrue() {
        User owner = criarUsuario("repo-owner-1@test.com");
        User guest = criarUsuario("repo-guest-1@test.com");
        Vehicle vehicle = criarVeiculo(owner);
        criarShare(vehicle, owner, guest, VehicleShareStatus.PENDING, null);

        boolean existe = vehicleShareRepository.existsByVehicleIdAndStatusIn(
                vehicle.getId(), List.of(VehicleShareStatus.PENDING, VehicleShareStatus.ACTIVE));

        assertThat(existe).isTrue();
    }

    @Test
    void existsByVehicleIdAndStatusIn_semShare_retornaFalse() {
        User owner = criarUsuario("repo-owner-2@test.com");
        Vehicle vehicle = criarVeiculo(owner);

        boolean existe = vehicleShareRepository.existsByVehicleIdAndStatusIn(
                vehicle.getId(), List.of(VehicleShareStatus.PENDING, VehicleShareStatus.ACTIVE));

        assertThat(existe).isFalse();
    }

    @Test
    void findFirstByVehicleIdAndStatusInOrderByCreatedAtDesc_retornaMaisRecente() {
        User owner = criarUsuario("repo-owner-3@test.com");
        User guest = criarUsuario("repo-guest-3@test.com");
        Vehicle vehicle = criarVeiculo(owner);
        criarShare(vehicle, owner, guest, VehicleShareStatus.REJECTED, null);
        VehicleShare ativo = criarShare(vehicle, owner, guest, VehicleShareStatus.ACTIVE, LocalDateTime.now().plusDays(1));

        Optional<VehicleShare> encontrado = vehicleShareRepository
                .findFirstByVehicleIdAndStatusInOrderByCreatedAtDesc(
                        vehicle.getId(), List.of(VehicleShareStatus.PENDING, VehicleShareStatus.ACTIVE));

        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getId()).isEqualTo(ativo.getId());
    }

    @Test
    void findByGuestIdAndStatus_retornaSharesDoConvidadoComStatus() {
        User owner = criarUsuario("repo-owner-4@test.com");
        User guest = criarUsuario("repo-guest-4@test.com");
        Vehicle vehicle = criarVeiculo(owner);
        criarShare(vehicle, owner, guest, VehicleShareStatus.PENDING, null);

        List<VehicleShare> pendentes = vehicleShareRepository.findByGuestIdAndStatus(
                guest.getId(), VehicleShareStatus.PENDING);

        assertThat(pendentes).hasSize(1);
    }

    @Test
    void findByGuestIdAndStatusAndExpiresAtAfter_ignoraShareExpirado() {
        User owner = criarUsuario("repo-owner-5@test.com");
        User guest = criarUsuario("repo-guest-5@test.com");
        Vehicle vehicle = criarVeiculo(owner);
        criarShare(vehicle, owner, guest, VehicleShareStatus.ACTIVE, LocalDateTime.now().minusHours(1));

        List<VehicleShare> ativos = vehicleShareRepository.findByGuestIdAndStatusAndExpiresAtAfter(
                guest.getId(), VehicleShareStatus.ACTIVE, LocalDateTime.now());

        assertThat(ativos).isEmpty();
    }

    @Test
    void existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter_shareAtivoDentroDoPrazo_retornaTrue() {
        User owner = criarUsuario("repo-owner-6@test.com");
        User guest = criarUsuario("repo-guest-6@test.com");
        Vehicle vehicle = criarVeiculo(owner);
        criarShare(vehicle, owner, guest, VehicleShareStatus.ACTIVE, LocalDateTime.now().plusDays(1));

        boolean temAcesso = vehicleShareRepository.existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter(
                vehicle.getId(), guest.getId(), VehicleShareStatus.ACTIVE, LocalDateTime.now());

        assertThat(temAcesso).isTrue();
    }
}
```

- [ ] **Step 3: Rodar o teste pra confirmar que falha (métodos derivados não existem no repositório)**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleShareRepositoryTest`
Expected: FAIL com erro de compilação (`cannot find symbol` para os métodos `existsByVehicleIdAndStatusIn`, etc.)

- [ ] **Step 4: Adicionar os métodos derivados ao repositório**

```java
package com.devappmobile.flowfuel.vehicleshare;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleShareRepository extends JpaRepository<VehicleShare, Long> {

    boolean existsByVehicleIdAndStatusIn(Long vehicleId, List<VehicleShareStatus> statuses);

    Optional<VehicleShare> findFirstByVehicleIdAndStatusInOrderByCreatedAtDesc(
            Long vehicleId, List<VehicleShareStatus> statuses);

    List<VehicleShare> findByGuestIdAndStatus(Long guestId, VehicleShareStatus status);

    List<VehicleShare> findByGuestIdAndStatusAndExpiresAtAfter(
            Long guestId, VehicleShareStatus status, LocalDateTime now);

    boolean existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter(
            Long vehicleId, Long guestId, VehicleShareStatus status, LocalDateTime now);
}
```

- [ ] **Step 5: Rodar o teste pra confirmar que passa**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleShareRepositoryTest`
Expected: PASS (6 testes)

- [ ] **Step 6: Commit**

```bash
cd ~/Projetos/flowfuel
git add src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareRepository.java \
        src/test/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareRepositoryTest.java
git commit -m "feat(vehicleshare): add VehicleShareRepository query methods"
```

---

### Task 3: `AuthorizationHelper.ensureOwnsOrHasGuestAccess`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/common/AuthorizationHelper.java`
- Test: `src/test/java/com/devappmobile/flowfuel/common/AuthorizationHelperTest.java` (novo — hoje esta classe não tem teste próprio; os `ensureOwns*` existentes são exercitados indiretamente pelos testes de service. Criamos o arquivo agora porque o novo método tem lógica condicional própria que merece cobertura direta.)

**Interfaces:**
- Consumes: `VehicleShareRepository.existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter(Long, Long, VehicleShareStatus, LocalDateTime)` (Task 2); `Vehicle.getUser()` (já existe).
- Produces: `AuthorizationHelper.ensureOwnsOrHasGuestAccess(User user, Vehicle vehicle)` — não retorna nada, lança `ForbiddenOperationException` se o usuário não for nem dono nem convidado com share ativo.

- [ ] **Step 1: Escrever o teste (vai falhar a compilar — o método não existe)**

```java
package com.devappmobile.flowfuel.common;

import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicleshare.VehicleShareRepository;
import com.devappmobile.flowfuel.vehicleshare.VehicleShareStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationHelperTest {

    @Mock
    private VehicleShareRepository vehicleShareRepository;

    @InjectMocks
    private AuthorizationHelper authorizationHelper;

    private User dono;
    private User convidado;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        dono = new User();
        dono.setId(1L);
        convidado = new User();
        convidado.setId(2L);
        vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setUser(dono);
    }

    @Test
    void ensureOwnsOrHasGuestAccess_dono_naoLancaNadaESemConsultarRepositorio() {
        assertThatCode(() -> authorizationHelper.ensureOwnsOrHasGuestAccess(dono, vehicle))
                .doesNotThrowAnyException();
    }

    @Test
    void ensureOwnsOrHasGuestAccess_convidadoComShareAtivo_naoLancaNada() {
        when(vehicleShareRepository.existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter(
                eq(10L), eq(2L), eq(VehicleShareStatus.ACTIVE), any()))
                .thenReturn(true);

        assertThatCode(() -> authorizationHelper.ensureOwnsOrHasGuestAccess(convidado, vehicle))
                .doesNotThrowAnyException();
    }

    @Test
    void ensureOwnsOrHasGuestAccess_convidadoSemShareAtivo_lancaForbidden() {
        when(vehicleShareRepository.existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter(
                eq(10L), eq(2L), eq(VehicleShareStatus.ACTIVE), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> authorizationHelper.ensureOwnsOrHasGuestAccess(convidado, vehicle))
                .isInstanceOf(ForbiddenOperationException.class);
    }
}
```

- [ ] **Step 2: Rodar o teste pra confirmar que falha (método não existe)**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=AuthorizationHelperTest`
Expected: FAIL com erro de compilação

- [ ] **Step 3: Adicionar o método à `AuthorizationHelper`** (a classe passa a ter dependência via construtor — troca `@Component` isolado por `@RequiredArgsConstructor`)

```java
package com.devappmobile.flowfuel.common;

import com.devappmobile.flowfuel.devicetoken.DeviceToken;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import com.devappmobile.flowfuel.vehicleshare.VehicleShareRepository;
import com.devappmobile.flowfuel.vehicleshare.VehicleShareStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class AuthorizationHelper {

    private final VehicleShareRepository vehicleShareRepository;

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

    public void ensureOwnsDeviceToken(User user, DeviceToken deviceToken) {
        if (!deviceToken.getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Token de dispositivo não pertence ao usuário");
        }
    }

    public void ensureIsAdmin(User user) {
        if (!user.isAdmin()) {
            throw new ForbiddenOperationException("Operação restrita a administradores");
        }
    }

    public void ensureOwnsOrHasGuestAccess(User user, Vehicle vehicle) {
        if (vehicle.getUser().getId().equals(user.getId())) {
            return;
        }
        boolean temAcessoDeConvidado = vehicleShareRepository.existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter(
                vehicle.getId(), user.getId(), VehicleShareStatus.ACTIVE, LocalDateTime.now());
        if (!temAcessoDeConvidado) {
            throw new ForbiddenOperationException("Veículo não pertence ao usuário nem está compartilhado com ele");
        }
    }
}
```

- [ ] **Step 4: Rodar o teste pra confirmar que passa**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=AuthorizationHelperTest`
Expected: PASS (3 testes)

- [ ] **Step 5: Rodar a suíte completa pra garantir que a mudança de construtor não quebrou nada** (outros testes que fazem `new AuthorizationHelper()` sem argumento, se existirem, vão quebrar)

Run: `cd ~/Projetos/flowfuel && mvn test`
Expected: BUILD SUCCESS — se algum teste existente instanciava `AuthorizationHelper` diretamente (sem Mockito), ajustar pra passar o mock de `VehicleShareRepository`.

- [ ] **Step 6: Commit**

```bash
cd ~/Projetos/flowfuel
git add src/main/java/com/devappmobile/flowfuel/common/AuthorizationHelper.java \
        src/test/java/com/devappmobile/flowfuel/common/AuthorizationHelperTest.java
git commit -m "feat(vehicleshare): add ensureOwnsOrHasGuestAccess authorization check"
```

---

### Task 4: `VehicleShareService` — criar, aceitar, recusar convite

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/vehicleshare/dto/VehicleShareRequestDTO.java`
- Create: `src/main/java/com/devappmobile/flowfuel/vehicleshare/dto/VehicleShareResponseDTO.java`
- Create: `src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareService.java`
- Test: `src/test/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareServiceTest.java`

**Interfaces:**
- Consumes: `VehicleShareRepository` (Task 2); `AuthorizationHelper.ensureOwnsVehicle` (já existente); `VehicleRepository.findById(Long)`, `UserRepository.findByEmail(String)` (já existentes); `PushNotificationService.sendPushToUser(Long, PushPayload)` e `PushPayload(String, String, String, String)` (`com.devappmobile.flowfuel.push`, já existentes).
- Produces: `VehicleShareService.create(User owner, VehicleShareRequestDTO request): VehicleShareResponseDTO`, `.accept(User user, Long id): VehicleShareResponseDTO`, `.reject(User user, Long id): VehicleShareResponseDTO`. `VehicleShareResponseDTO.from(VehicleShare): VehicleShareResponseDTO` (usado por Task 5 e 6 também).

- [ ] **Step 1: Criar os DTOs**

```java
package com.devappmobile.flowfuel.vehicleshare.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VehicleShareRequestDTO {

    @NotNull
    private Long vehicleId;

    @NotBlank
    @Email
    private String inviteeEmail;

    @NotNull
    @Min(1)
    @Max(365)
    private Integer durationDays;
}
```

```java
package com.devappmobile.flowfuel.vehicleshare.dto;

import com.devappmobile.flowfuel.vehicleshare.VehicleShare;
import com.devappmobile.flowfuel.vehicleshare.VehicleShareStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleShareResponseDTO {

    private Long id;
    private Long vehicleId;
    private String vehicleBrand;
    private String vehicleModel;
    private Long ownerId;
    private String ownerName;
    private Long guestId;
    private String guestName;
    private VehicleShareStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime respondedAt;
    private LocalDateTime expiresAt;

    public static VehicleShareResponseDTO from(VehicleShare entity) {
        return VehicleShareResponseDTO.builder()
                .id(entity.getId())
                .vehicleId(entity.getVehicle().getId())
                .vehicleBrand(entity.getVehicle().getBrand())
                .vehicleModel(entity.getVehicle().getModel())
                .ownerId(entity.getOwner().getId())
                .ownerName(entity.getOwner().getName())
                .guestId(entity.getGuest().getId())
                .guestName(entity.getGuest().getName())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .respondedAt(entity.getRespondedAt())
                .expiresAt(entity.getExpiresAt())
                .build();
    }
}
```

- [ ] **Step 2: Escrever os testes de `create`/`accept`/`reject` (vai falhar a compilar — `VehicleShareService` não existe)**

```java
package com.devappmobile.flowfuel.vehicleshare;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.push.PushNotificationService;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareRequestDTO;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VehicleShareServiceTest {

    @Mock
    private VehicleShareRepository vehicleShareRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthorizationHelper authorizationHelper;
    @Mock
    private PushNotificationService pushNotificationService;

    @InjectMocks
    private VehicleShareService vehicleShareService;

    private User owner;
    private User guest;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@test.com");
        owner.setName("Dono");

        guest = new User();
        guest.setId(2L);
        guest.setEmail("guest@test.com");
        guest.setName("Convidado");

        vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setUser(owner);
        vehicle.setBrand("Toyota");
        vehicle.setModel("Corolla");
    }

    private VehicleShareRequestDTO requestValido() {
        VehicleShareRequestDTO request = new VehicleShareRequestDTO();
        request.setVehicleId(10L);
        request.setInviteeEmail("guest@test.com");
        request.setDurationDays(3);
        return request;
    }

    @Test
    void create_convitesValido_criaComStatusPendingEEnviaPush() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(userRepository.findByEmail("guest@test.com")).thenReturn(Optional.of(guest));
        when(vehicleShareRepository.existsByVehicleIdAndStatusIn(eq(10L), any())).thenReturn(false);
        when(vehicleShareRepository.save(any(VehicleShare.class)))
                .thenAnswer(invocation -> {
                    VehicleShare share = invocation.getArgument(0);
                    share.setId(100L);
                    return share;
                });

        VehicleShareResponseDTO response = vehicleShareService.create(owner, requestValido());

        assertThat(response.getStatus()).isEqualTo(VehicleShareStatus.PENDING);
        assertThat(response.getGuestId()).isEqualTo(2L);
        verify(pushNotificationService).sendPushToUser(eq(2L), any());
    }

    @Test
    void create_convidarASiMesmo_lancaBusinessRuleException() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        VehicleShareRequestDTO request = requestValido();
        request.setInviteeEmail("owner@test.com");

        assertThatThrownBy(() -> vehicleShareService.create(owner, request))
                .isInstanceOf(BusinessRuleException.class);

        verify(vehicleShareRepository, never()).save(any());
    }

    @Test
    void create_emailNaoCadastrado_lancaResourceNotFoundException() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(userRepository.findByEmail("guest@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleShareService.create(owner, requestValido()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_veiculoJaTemSharePendenteOuAtivo_lancaConflictException() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(userRepository.findByEmail("guest@test.com")).thenReturn(Optional.of(guest));
        when(vehicleShareRepository.existsByVehicleIdAndStatusIn(eq(10L), any())).thenReturn(true);

        assertThatThrownBy(() -> vehicleShareService.create(owner, requestValido()))
                .isInstanceOf(ConflictException.class);

        verify(vehicleShareRepository, never()).save(any());
    }

    private VehicleShare shareExistente(VehicleShareStatus status) {
        VehicleShare share = new VehicleShare();
        share.setId(100L);
        share.setVehicle(vehicle);
        share.setOwner(owner);
        share.setGuest(guest);
        share.setStatus(status);
        share.setDurationDays(3);
        return share;
    }

    @Test
    void accept_convitePendenteEGuestCorreto_ativaECalculaExpiresAt() {
        VehicleShare share = shareExistente(VehicleShareStatus.PENDING);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));
        when(vehicleShareRepository.save(any(VehicleShare.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VehicleShareResponseDTO response = vehicleShareService.accept(guest, 100L);

        assertThat(response.getStatus()).isEqualTo(VehicleShareStatus.ACTIVE);
        assertThat(response.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(2));
        assertThat(response.getExpiresAt()).isBefore(LocalDateTime.now().plusDays(4));
    }

    @Test
    void accept_naoEhOGuestDoConvite_lancaForbidden() {
        VehicleShare share = shareExistente(VehicleShareStatus.PENDING);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> vehicleShareService.accept(owner, 100L))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void accept_conviteJaRespondido_lancaBusinessRuleException() {
        VehicleShare share = shareExistente(VehicleShareStatus.ACTIVE);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> vehicleShareService.accept(guest, 100L))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void reject_convitePendenteEGuestCorreto_marcaRejected() {
        VehicleShare share = shareExistente(VehicleShareStatus.PENDING);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));
        when(vehicleShareRepository.save(any(VehicleShare.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VehicleShareResponseDTO response = vehicleShareService.reject(guest, 100L);

        assertThat(response.getStatus()).isEqualTo(VehicleShareStatus.REJECTED);
    }

    @Test
    void reject_naoEhOGuestDoConvite_lancaForbidden() {
        VehicleShare share = shareExistente(VehicleShareStatus.PENDING);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> vehicleShareService.reject(owner, 100L))
                .isInstanceOf(ForbiddenOperationException.class);
    }
}
```

- [ ] **Step 3: Rodar o teste pra confirmar que falha (classe não existe)**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleShareServiceTest`
Expected: FAIL com erro de compilação

- [ ] **Step 4: Criar `VehicleShareService` com `create`/`accept`/`reject`**

```java
package com.devappmobile.flowfuel.vehicleshare;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.push.PushNotificationService;
import com.devappmobile.flowfuel.push.PushPayload;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareRequestDTO;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleShareService {

    private final VehicleShareRepository vehicleShareRepository;
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final AuthorizationHelper authorizationHelper;
    private final PushNotificationService pushNotificationService;

    @Transactional
    public VehicleShareResponseDTO create(User owner, VehicleShareRequestDTO request) {
        Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", request.getVehicleId()));
        authorizationHelper.ensureOwnsVehicle(owner, vehicle);

        if (request.getInviteeEmail().equalsIgnoreCase(owner.getEmail())) {
            throw new BusinessRuleException("Não é possível compartilhar o veículo com você mesmo");
        }

        User guest = userRepository.findByEmail(request.getInviteeEmail())
                .orElseThrow(() -> new ResourceNotFoundException("Usuário", request.getInviteeEmail()));

        if (vehicleShareRepository.existsByVehicleIdAndStatusIn(vehicle.getId(),
                List.of(VehicleShareStatus.PENDING, VehicleShareStatus.ACTIVE))) {
            throw new ConflictException("Veículo já possui um compartilhamento pendente ou ativo");
        }

        VehicleShare share = new VehicleShare();
        share.setVehicle(vehicle);
        share.setOwner(owner);
        share.setGuest(guest);
        share.setStatus(VehicleShareStatus.PENDING);
        share.setDurationDays(request.getDurationDays());
        VehicleShare saved = vehicleShareRepository.save(share);

        pushNotificationService.sendPushToUser(guest.getId(), new PushPayload(
                "Convite de veículo",
                "%s quer compartilhar o %s %s com você".formatted(owner.getName(), vehicle.getBrand(), vehicle.getModel()),
                "flowfuel://vehicle-share/" + saved.getId(),
                "vehicle_share_invite"));

        return VehicleShareResponseDTO.from(saved);
    }

    @Transactional
    public VehicleShareResponseDTO accept(User user, Long id) {
        VehicleShare share = vehicleShareRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Compartilhamento", id));
        ensureIsGuest(user, share);
        ensureIsPending(share);

        share.setStatus(VehicleShareStatus.ACTIVE);
        share.setRespondedAt(LocalDateTime.now());
        share.setExpiresAt(LocalDateTime.now().plusDays(share.getDurationDays()));

        return VehicleShareResponseDTO.from(vehicleShareRepository.save(share));
    }

    @Transactional
    public VehicleShareResponseDTO reject(User user, Long id) {
        VehicleShare share = vehicleShareRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Compartilhamento", id));
        ensureIsGuest(user, share);
        ensureIsPending(share);

        share.setStatus(VehicleShareStatus.REJECTED);
        share.setRespondedAt(LocalDateTime.now());

        return VehicleShareResponseDTO.from(vehicleShareRepository.save(share));
    }

    private void ensureIsGuest(User user, VehicleShare share) {
        if (!share.getGuest().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Convite não pertence ao usuário");
        }
    }

    private void ensureIsPending(VehicleShare share) {
        if (share.getStatus() != VehicleShareStatus.PENDING) {
            throw new BusinessRuleException("Convite não está mais disponível");
        }
    }
}
```

- [ ] **Step 5: Rodar o teste pra confirmar que passa**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleShareServiceTest`
Expected: PASS (8 testes)

- [ ] **Step 6: Commit**

```bash
cd ~/Projetos/flowfuel
git add src/main/java/com/devappmobile/flowfuel/vehicleshare/dto/VehicleShareRequestDTO.java \
        src/main/java/com/devappmobile/flowfuel/vehicleshare/dto/VehicleShareResponseDTO.java \
        src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareService.java \
        src/test/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareServiceTest.java
git commit -m "feat(vehicleshare): add VehicleShareService create/accept/reject"
```

---

### Task 5: `VehicleShareService` — revogar e consultas (dono e convidado)

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareServiceTest.java`

**Interfaces:**
- Consumes: mesmos de Task 4.
- Produces: `VehicleShareService.revoke(User owner, Long id): void`, `.getForVehicle(User user, Long vehicleId): VehicleShareResponseDTO` (ou `null` se não houver share pendente/ativo), `.listPendingForGuest(User user): List<VehicleShareResponseDTO>`, `.listActiveForGuest(User user): List<VehicleShareResponseDTO>`.

- [ ] **Step 1: Adicionar os testes ao final de `VehicleShareServiceTest`** (mesmo arquivo da Task 4 — vai falhar a compilar, os métodos ainda não existem no service)

```java
    @Test
    void revoke_donoCorreto_marcaRevoked() {
        VehicleShare share = shareExistente(VehicleShareStatus.ACTIVE);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));
        when(vehicleShareRepository.save(any(VehicleShare.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        vehicleShareService.revoke(owner, 100L);

        ArgumentCaptor<VehicleShare> captor = ArgumentCaptor.forClass(VehicleShare.class);
        verify(vehicleShareRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(VehicleShareStatus.REVOKED);
    }

    @Test
    void revoke_naoEhODono_lancaForbidden() {
        VehicleShare share = shareExistente(VehicleShareStatus.ACTIVE);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> vehicleShareService.revoke(guest, 100L))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(vehicleShareRepository, never()).save(any());
    }

    @Test
    void getForVehicle_comShareAtivo_retornaDto() {
        Vehicle owned = vehicle;
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(owned));
        VehicleShare share = shareExistente(VehicleShareStatus.ACTIVE);
        when(vehicleShareRepository.findFirstByVehicleIdAndStatusInOrderByCreatedAtDesc(eq(10L), any()))
                .thenReturn(Optional.of(share));

        VehicleShareResponseDTO response = vehicleShareService.getForVehicle(owner, 10L);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(100L);
    }

    @Test
    void getForVehicle_semShare_retornaNull() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleShareRepository.findFirstByVehicleIdAndStatusInOrderByCreatedAtDesc(eq(10L), any()))
                .thenReturn(Optional.empty());

        VehicleShareResponseDTO response = vehicleShareService.getForVehicle(owner, 10L);

        assertThat(response).isNull();
    }

    @Test
    void listPendingForGuest_retornaConvitesPendentesDoUsuario() {
        VehicleShare share = shareExistente(VehicleShareStatus.PENDING);
        when(vehicleShareRepository.findByGuestIdAndStatus(2L, VehicleShareStatus.PENDING))
                .thenReturn(List.of(share));

        List<VehicleShareResponseDTO> pendentes = vehicleShareService.listPendingForGuest(guest);

        assertThat(pendentes).hasSize(1);
        assertThat(pendentes.get(0).getStatus()).isEqualTo(VehicleShareStatus.PENDING);
    }

    @Test
    void listActiveForGuest_retornaVeiculosComShareAtivo() {
        VehicleShare share = shareExistente(VehicleShareStatus.ACTIVE);
        when(vehicleShareRepository.findByGuestIdAndStatusAndExpiresAtAfter(eq(2L), eq(VehicleShareStatus.ACTIVE), any()))
                .thenReturn(List.of(share));

        List<VehicleShareResponseDTO> ativos = vehicleShareService.listActiveForGuest(guest);

        assertThat(ativos).hasSize(1);
    }
```

(Adicionar esses métodos como novos `@Test` dentro da classe `VehicleShareServiceTest`, antes da chave de fechamento final.)

- [ ] **Step 2: Rodar o teste pra confirmar que falha (métodos não existem no service)**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleShareServiceTest`
Expected: FAIL com erro de compilação

- [ ] **Step 3: Adicionar os métodos ao `VehicleShareService`**

```java
    @Transactional
    public void revoke(User owner, Long id) {
        VehicleShare share = vehicleShareRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Compartilhamento", id));
        if (!share.getOwner().getId().equals(owner.getId())) {
            throw new ForbiddenOperationException("Compartilhamento não pertence ao usuário");
        }
        share.setStatus(VehicleShareStatus.REVOKED);
        share.setRespondedAt(LocalDateTime.now());
        vehicleShareRepository.save(share);
    }

    public VehicleShareResponseDTO getForVehicle(User user, Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));
        authorizationHelper.ensureOwnsVehicle(user, vehicle);

        return vehicleShareRepository
                .findFirstByVehicleIdAndStatusInOrderByCreatedAtDesc(vehicleId,
                        List.of(VehicleShareStatus.PENDING, VehicleShareStatus.ACTIVE))
                .map(VehicleShareResponseDTO::from)
                .orElse(null);
    }

    public List<VehicleShareResponseDTO> listPendingForGuest(User user) {
        return vehicleShareRepository.findByGuestIdAndStatus(user.getId(), VehicleShareStatus.PENDING)
                .stream()
                .map(VehicleShareResponseDTO::from)
                .toList();
    }

    public List<VehicleShareResponseDTO> listActiveForGuest(User user) {
        return vehicleShareRepository
                .findByGuestIdAndStatusAndExpiresAtAfter(user.getId(), VehicleShareStatus.ACTIVE, LocalDateTime.now())
                .stream()
                .map(VehicleShareResponseDTO::from)
                .toList();
    }
```

(Adicionar dentro da classe `VehicleShareService`, depois do método `reject`.)

- [ ] **Step 4: Rodar o teste pra confirmar que passa**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleShareServiceTest`
Expected: PASS (14 testes no total do arquivo)

- [ ] **Step 5: Commit**

```bash
cd ~/Projetos/flowfuel
git add src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareService.java \
        src/test/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareServiceTest.java
git commit -m "feat(vehicleshare): add revoke and query methods to VehicleShareService"
```

---

### Task 6: `VehicleShareController` + teste de integração ponta a ponta

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareController.java`
- Test: `src/test/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `VehicleShareService` (Tasks 4 e 5).
- Produces: endpoints REST em `/vehicle-shares` (prefixo `/api/v1` aplicado globalmente, igual aos demais controllers): `POST /`, `POST /{id}/accept`, `POST /{id}/reject`, `DELETE /{id}`, `GET /vehicle/{vehicleId}`, `GET /pending`, `GET /active-for-me`.

- [ ] **Step 1: Escrever o teste de integração (vai falhar a compilar — controller não existe; e falhar em runtime com 404 até o mapping existir)**

```java
package com.devappmobile.flowfuel.vehicleshare;

import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VehicleShareControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private VehicleShareRepository vehicleShareRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void limparBanco() {
        vehicleShareRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String registrarEAtivar(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123","name":"User"}
                        """.formatted(email)));
        userRepository.findByEmail(email).ifPresent(u -> {
            u.setStatus(UserStatus.ACTIVE);
            userRepository.save(u);
        });
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123"}
                        """.formatted(email)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private Long criarVeiculo(String jwtDono) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/vehicles")
                .header("Authorization", "Bearer " + jwtDono)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"CARRO","energyType":"COMBUSTION","currentKm":1000,"capacity":50,"brand":"Toyota","model":"Corolla"}
                        """))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void fluxoCompleto_convidarAceitarLancarEventoRevogar() throws Exception {
        String jwtDono = registrarEAtivar("share-owner@test.com");
        String jwtConvidado = registrarEAtivar("share-guest@test.com");
        Long vehicleId = criarVeiculo(jwtDono);

        // Dono convida o convidado
        MvcResult conviteResult = mockMvc.perform(post("/api/v1/vehicle-shares")
                .header("Authorization", "Bearer " + jwtDono)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"inviteeEmail":"share-guest@test.com","durationDays":3}
                        """.formatted(vehicleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        Long shareId = objectMapper.readTree(conviteResult.getResponse().getContentAsString()).get("id").asLong();

        // Convidado vê o convite pendente
        mockMvc.perform(get("/api/v1/vehicle-shares/pending")
                .header("Authorization", "Bearer " + jwtConvidado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(shareId));

        // Convidado aceita
        mockMvc.perform(post("/api/v1/vehicle-shares/{id}/accept", shareId)
                .header("Authorization", "Bearer " + jwtConvidado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Convidado vê o veículo em "compartilhados comigo"
        mockMvc.perform(get("/api/v1/vehicle-shares/active-for-me")
                .header("Authorization", "Bearer " + jwtConvidado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vehicleId").value(vehicleId.intValue()));

        // Convidado lança evento de categoria permitida (FUEL)
        mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + jwtConvidado)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"type":"FUEL","amount":150.00,"eventDate":"2026-07-14"}
                        """.formatted(vehicleId)))
                .andExpect(status().isOk());

        // Convidado tenta lançar categoria não permitida (INSURANCE) → 403
        mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + jwtConvidado)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"type":"INSURANCE","amount":500.00,"eventDate":"2026-07-14"}
                        """.formatted(vehicleId)))
                .andExpect(status().isForbidden());

        // Convidado atualiza o odômetro
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/vehicles/{id}/odometer?currentKm=1050", vehicleId)
                .header("Authorization", "Bearer " + jwtConvidado))
                .andExpect(status().isOk());

        // Dono revoga
        mockMvc.perform(delete("/api/v1/vehicle-shares/{id}", shareId)
                .header("Authorization", "Bearer " + jwtDono))
                .andExpect(status().isNoContent());

        // Convidado perde acesso
        mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + jwtConvidado)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"type":"FUEL","amount":100.00,"eventDate":"2026-07-14"}
                        """.formatted(vehicleId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_conviteDuplicadoEnquantoPendente_retorna409() throws Exception {
        String jwtDono = registrarEAtivar("share-dup-owner@test.com");
        registrarEAtivar("share-dup-guest@test.com");
        Long vehicleId = criarVeiculo(jwtDono);

        mockMvc.perform(post("/api/v1/vehicle-shares")
                .header("Authorization", "Bearer " + jwtDono)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"inviteeEmail":"share-dup-guest@test.com","durationDays":1}
                        """.formatted(vehicleId)));

        mockMvc.perform(post("/api/v1/vehicle-shares")
                .header("Authorization", "Bearer " + jwtDono)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"inviteeEmail":"share-dup-guest@test.com","durationDays":1}
                        """.formatted(vehicleId)))
                .andExpect(status().isConflict());
    }

    @Test
    void accept_usuarioQueNaoEhOConvidado_retorna403() throws Exception {
        String jwtDono = registrarEAtivar("share-forbid-owner@test.com");
        registrarEAtivar("share-forbid-guest@test.com");
        String jwtOutro = registrarEAtivar("share-forbid-outro@test.com");
        Long vehicleId = criarVeiculo(jwtDono);

        MvcResult conviteResult = mockMvc.perform(post("/api/v1/vehicle-shares")
                .header("Authorization", "Bearer " + jwtDono)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"inviteeEmail":"share-forbid-guest@test.com","durationDays":1}
                        """.formatted(vehicleId)))
                .andReturn();
        Long shareId = objectMapper.readTree(conviteResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/vehicle-shares/{id}/accept", shareId)
                .header("Authorization", "Bearer " + jwtOutro))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Rodar o teste pra confirmar que falha**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleShareControllerIntegrationTest`
Expected: FAIL com erro de compilação (`VehicleShareController` não existe) ou 404 se compilar sem o mapping

- [ ] **Step 3: Criar o controller**

```java
package com.devappmobile.flowfuel.vehicleshare;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareRequestDTO;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/vehicle-shares")
@RequiredArgsConstructor
public class VehicleShareController {

    private final VehicleShareService vehicleShareService;

    @PostMapping
    public VehicleShareResponseDTO create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody VehicleShareRequestDTO request) {
        return vehicleShareService.create(user, request);
    }

    @PostMapping("/{id}/accept")
    public VehicleShareResponseDTO accept(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return vehicleShareService.accept(user, id);
    }

    @PostMapping("/{id}/reject")
    public VehicleShareResponseDTO reject(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return vehicleShareService.reject(user, id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal User user, @PathVariable Long id) {
        vehicleShareService.revoke(user, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/vehicle/{vehicleId}")
    public ResponseEntity<VehicleShareResponseDTO> getForVehicle(
            @AuthenticationPrincipal User user, @PathVariable Long vehicleId) {
        VehicleShareResponseDTO dto = vehicleShareService.getForVehicle(user, vehicleId);
        return dto != null ? ResponseEntity.ok(dto) : ResponseEntity.noContent().build();
    }

    @GetMapping("/pending")
    public List<VehicleShareResponseDTO> listPending(@AuthenticationPrincipal User user) {
        return vehicleShareService.listPendingForGuest(user);
    }

    @GetMapping("/active-for-me")
    public List<VehicleShareResponseDTO> listActiveForMe(@AuthenticationPrincipal User user) {
        return vehicleShareService.listActiveForGuest(user);
    }
}
```

- [ ] **Step 4: Rodar o teste pra confirmar que passa** (esse teste só vai ficar totalmente verde depois das Tasks 7 e 8 — o passo do fluxo completo que espera 403 em categoria não permitida depende da Task 7; até lá, comentar temporariamente os dois blocos de asserção de evento/odômetro no fluxo completo não é necessário se as Tasks forem executadas em ordem)

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleShareControllerIntegrationTest`
Expected: PASS depois que as Tasks 7 e 8 também estiverem aplicadas (ver nota acima). Os testes `create_conviteDuplicadoEnquantoPendente_retorna409` e `accept_usuarioQueNaoEhOConvidado_retorna403` já passam só com esta task.

- [ ] **Step 5: Commit**

```bash
cd ~/Projetos/flowfuel
git add src/main/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareController.java \
        src/test/java/com/devappmobile/flowfuel/vehicleshare/VehicleShareControllerIntegrationTest.java
git commit -m "feat(vehicleshare): add VehicleShareController REST endpoints"
```

---

### Task 7: Restringir categorias do convidado em `VehicleEventService.create`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventServiceTest.java` (se não existir, criar seguindo o padrão de `DeviceTokenServiceTest`)

**Interfaces:**
- Consumes: `AuthorizationHelper.ensureOwnsOrHasGuestAccess(User, Vehicle)` (Task 3).
- Produces: `VehicleEventService.create` passa a aceitar chamadas de convidado com share ativo, restrito às categorias `FUEL`, `CAR_WASH`, `TIRES`, `OTHER`.

- [ ] **Step 1: Verificar se já existe teste pra `VehicleEventService.create`**

Run: `cd ~/Projetos/flowfuel && find src/test -iname 'VehicleEventServiceTest.java'`

Se existir, abrir o arquivo e seguir a partir do Step 2 adicionando os testes abaixo nele. Se não existir, criar o arquivo com esses dois testes (adaptando os mocks de `VehicleEventRepository`/`VehicleRepository`/`AuthorizationHelper` ao padrão usado — ver `DeviceTokenServiceTest` como referência de estilo).

- [ ] **Step 2: Escrever os testes (vão falhar — hoje `create` chama `ensureOwnsVehicle`, que não existe path pra convidado)**

```java
    @Test
    void create_convidadoComShareAtivoECategoriaPermitida_criaEvento() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(10L);
        User dono = new User();
        dono.setId(1L);
        vehicle.setUser(dono);

        User convidado = new User();
        convidado.setId(2L);

        VehicleEventRequestDTO request = new VehicleEventRequestDTO();
        request.setVehicleId(10L);
        request.setType(VehicleEventType.FUEL);
        request.setAmount(new java.math.BigDecimal("150.00"));
        request.setEventDate(java.time.LocalDate.now());

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleEventRepository.save(any(VehicleEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VehicleEventResponseDTO response = vehicleEventService.create(convidado, request);

        assertThat(response.getType()).isEqualTo(VehicleEventType.FUEL);
        verify(authorizationHelper).ensureOwnsOrHasGuestAccess(convidado, vehicle);
    }

    @Test
    void create_convidadoComShareAtivoECategoriaNaoPermitida_lancaForbidden() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(10L);
        User dono = new User();
        dono.setId(1L);
        vehicle.setUser(dono);

        User convidado = new User();
        convidado.setId(2L);

        VehicleEventRequestDTO request = new VehicleEventRequestDTO();
        request.setVehicleId(10L);
        request.setType(VehicleEventType.INSURANCE);
        request.setAmount(new java.math.BigDecimal("500.00"));
        request.setEventDate(java.time.LocalDate.now());

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> vehicleEventService.create(convidado, request))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(vehicleEventRepository, never()).save(any());
    }

    @Test
    void create_donoLancandoQualquerCategoria_naoRestringe() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(10L);
        User dono = new User();
        dono.setId(1L);
        vehicle.setUser(dono);

        VehicleEventRequestDTO request = new VehicleEventRequestDTO();
        request.setVehicleId(10L);
        request.setType(VehicleEventType.INSURANCE);
        request.setAmount(new java.math.BigDecimal("500.00"));
        request.setEventDate(java.time.LocalDate.now());

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleEventRepository.save(any(VehicleEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VehicleEventResponseDTO response = vehicleEventService.create(dono, request);

        assertThat(response.getType()).isEqualTo(VehicleEventType.INSURANCE);
    }
```

(Se o arquivo de teste precisar ser criado do zero, usar este cabeçalho de classe — mocks de `VehicleEventRepository`, `VehicleRepository` e `AuthorizationHelper`, `@InjectMocks VehicleEventService vehicleEventService`, mesmo padrão de `DeviceTokenServiceTest`.)

- [ ] **Step 3: Rodar o teste pra confirmar que falha**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleEventServiceTest`
Expected: FAIL — `create_convidadoComShareAtivoECategoriaPermitida_criaEvento` falha porque hoje `create` chama `ensureOwnsVehicle` (que lançaria forbidden pro convidado se o mock estivesse configurado pra isso — como o teste verifica `ensureOwnsOrHasGuestAccess`, que ainda não é chamado, o `verify` falha), e `create_convidadoComShareAtivoECategoriaNaoPermitida_lancaForbidden` falha porque hoje não existe checagem de categoria.

- [ ] **Step 4: Alterar `VehicleEventService.create`**

```java
package com.devappmobile.flowfuel.vehicleevent;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.common.PageResponseDTO;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleevent.dto.VehicleEventRequestDTO;
import com.devappmobile.flowfuel.vehicleevent.dto.VehicleEventResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class VehicleEventService {

    private static final Set<VehicleEventType> GUEST_ALLOWED_TYPES =
            Set.of(VehicleEventType.FUEL, VehicleEventType.CAR_WASH, VehicleEventType.TIRES, VehicleEventType.OTHER);

    private final VehicleEventRepository vehicleEventRepository;
    private final VehicleRepository vehicleRepository;
    private final AuthorizationHelper authorizationHelper;

    public VehicleEventResponseDTO create(User user, VehicleEventRequestDTO request) {
        Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", request.getVehicleId()));

        authorizationHelper.ensureOwnsOrHasGuestAccess(user, vehicle);

        boolean isGuest = !vehicle.getUser().getId().equals(user.getId());
        if (isGuest && !GUEST_ALLOWED_TYPES.contains(request.getType())) {
            throw new ForbiddenOperationException("Categoria não permitida para veículo emprestado");
        }

        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);
        event.setType(request.getType());
        event.setAmount(request.getAmount());
        event.setEventDate(request.getEventDate());
        event.setOdometer(request.getOdometer());
        event.setDescription(request.getDescription());

        return VehicleEventResponseDTO.from(vehicleEventRepository.save(event));
    }

    public VehicleEventResponseDTO getById(User user, Long id) {
        VehicleEvent event = vehicleEventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evento", id));
        authorizationHelper.ensureOwnsEvent(user, event);
        return VehicleEventResponseDTO.from(event);
    }

    public VehicleEventResponseDTO update(User user, Long id, VehicleEventRequestDTO request) {
        VehicleEvent event = vehicleEventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evento", id));
        authorizationHelper.ensureOwnsEvent(user, event);

        if (request.getType() != null) event.setType(request.getType());
        if (request.getAmount() != null) event.setAmount(request.getAmount());
        if (request.getDescription() != null) event.setDescription(request.getDescription());
        if (request.getOdometer() != null) event.setOdometer(request.getOdometer());
        if (request.getEventDate() != null) event.setEventDate(request.getEventDate());

        return VehicleEventResponseDTO.from(vehicleEventRepository.save(event));
    }

    public void delete(User user, Long id) {
        VehicleEvent event = vehicleEventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evento", id));
        authorizationHelper.ensureOwnsEvent(user, event);
        vehicleEventRepository.deleteById(id);
    }

    public PageResponseDTO<VehicleEventResponseDTO> getVehicleEvents(User user, Long vehicleId,
            VehicleEventType type, LocalDate startDate, LocalDate endDate, Pageable pageable) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));
        authorizationHelper.ensureOwnsVehicle(user, vehicle);

        Page<VehicleEvent> page;
        if (type != null && startDate != null && endDate != null) {
            page = vehicleEventRepository
                    .findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                            vehicleId, type, startDate, endDate, pageable);
        } else if (type != null) {
            page = vehicleEventRepository
                    .findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(
                            vehicleId, type, pageable);
        } else if (startDate != null && endDate != null) {
            page = vehicleEventRepository
                    .findByVehicleIdAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                            vehicleId, startDate, endDate, pageable);
        } else {
            page = vehicleEventRepository
                    .findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(vehicleId, pageable);
        }

        return PageResponseDTO.from(page, VehicleEventResponseDTO::from);
    }

}
```

(Único trecho alterado de fato: o método `create` — os demais métodos permanecem exatamente como estavam, continuam usando `ensureOwnsEvent`/`ensureOwnsVehicle` puro.)

- [ ] **Step 5: Rodar o teste pra confirmar que passa**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleEventServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd ~/Projetos/flowfuel
git add src/main/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventService.java \
        src/test/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventServiceTest.java
git commit -m "feat(vehicleshare): restrict guest event categories to daily-use types"
```

---

### Task 8: Permitir convidado atualizar odômetro em `VehicleService.updateOdometer`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java` (se não existir, criar seguindo o padrão de `DeviceTokenServiceTest`)

**Interfaces:**
- Consumes: `AuthorizationHelper.ensureOwnsOrHasGuestAccess(User, Vehicle)` (Task 3).
- Produces: `VehicleService.updateOdometer` passa a aceitar chamadas de convidado com share ativo (sem restrição de categoria).

- [ ] **Step 1: Verificar se já existe teste pra `VehicleService.updateOdometer`**

Run: `cd ~/Projetos/flowfuel && find src/test -iname 'VehicleServiceTest.java'`

- [ ] **Step 2: Escrever os testes (vão falhar — hoje `updateOdometer` usa `findOwned`, que lança forbidden pro convidado mesmo com share ativo)**

```java
    @Test
    void updateOdometer_convidadoComShareAtivo_atualiza() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setCurrentKm(1000);
        User dono = new User();
        dono.setId(1L);
        vehicle.setUser(dono);

        User convidado = new User();
        convidado.setId(2L);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VehicleResponseDTO response = vehicleService.updateOdometer(convidado, 10L, 1050);

        assertThat(response.getCurrentKm()).isEqualTo(1050);
        verify(authorizationHelper).ensureOwnsOrHasGuestAccess(convidado, vehicle);
    }

    @Test
    void updateOdometer_usuarioSemAcesso_lancaForbidden() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setCurrentKm(1000);
        User dono = new User();
        dono.setId(1L);
        vehicle.setUser(dono);

        User semAcesso = new User();
        semAcesso.setId(3L);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        org.mockito.Mockito.doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário nem está compartilhado com ele"))
                .when(authorizationHelper).ensureOwnsOrHasGuestAccess(semAcesso, vehicle);

        assertThatThrownBy(() -> vehicleService.updateOdometer(semAcesso, 10L, 1050))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(vehicleRepository, never()).save(any());
    }
```

(Se o arquivo precisar ser criado do zero: mocks de `VehicleRepository`, `UserRepository`, `AuthorizationHelper`, `StorageService`; `@InjectMocks VehicleService vehicleService`, mesmo padrão de `DeviceTokenServiceTest`.)

- [ ] **Step 3: Rodar o teste pra confirmar que falha**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleServiceTest`
Expected: FAIL

- [ ] **Step 4: Alterar `VehicleService.updateOdometer` e adicionar `findOwnedOrGuestAccess`**

```java
    public VehicleResponseDTO updateOdometer(User user, Long id, Integer currentKm) {
        Vehicle vehicle = findOwnedOrGuestAccess(user, id);
        if (currentKm < vehicle.getCurrentKm()) {
            throw new BusinessRuleException("Odômetro não pode ser menor que o atual");
        }
        vehicle.setCurrentKm(currentKm);
        return VehicleResponseDTO.from(vehicleRepository.save(vehicle));
    }
```

(substitui a chamada `findOwned(user, id)` por `findOwnedOrGuestAccess(user, id)` dentro de `updateOdometer` — as outras chamadas de `findOwned` no restante da classe não mudam)

```java
    private Vehicle findOwnedOrGuestAccess(User user, Long id) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", id));
        authorizationHelper.ensureOwnsOrHasGuestAccess(user, vehicle);
        return vehicle;
    }
```

(novo método privado, colocado ao lado de `findOwned` no final da classe)

- [ ] **Step 5: Rodar o teste pra confirmar que passa**

Run: `cd ~/Projetos/flowfuel && mvn test -Dtest=VehicleServiceTest`
Expected: PASS

- [ ] **Step 6: Rodar a suíte completa do projeto** (agora que Tasks 6, 7 e 8 estão todas aplicadas, o teste de fluxo completo de `VehicleShareControllerIntegrationTest` deve passar por inteiro)

Run: `cd ~/Projetos/flowfuel && mvn test`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
cd ~/Projetos/flowfuel
git add src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java \
        src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java
git commit -m "feat(vehicleshare): allow guest to update odometer of shared vehicle"
```

---

## Verificação manual (fora do escopo automatizado)

A migração `V11__vehicle_shares.sql` (incluindo o índice único parcial `idx_vehicle_shares_active_unique`) só roda em staging/prod — os testes usam `ddl-auto=create-drop` a partir das entidades. Antes de considerar a feature pronta pra uso real, subir em staging (`flowfuel.push.enabled=true` já configurado, ver [[project_push_notification_frontend]]) e confirmar manualmente: convite → push chega no dispositivo → aceite → lançamento de evento/odômetro pelo convidado → revogação → convidado perde acesso.
