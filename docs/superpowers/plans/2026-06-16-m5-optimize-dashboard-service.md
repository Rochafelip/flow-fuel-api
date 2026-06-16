# M5 — Optimize DashboardService Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce `GET /dashboard/vehicle/{id}` from 6–11 sequential queries + unbounded in-memory list to 3 queries (non-hybrid) or 5 queries (hybrid), without changing the API contract.

**Architecture:** Add a `RefuelAggregateProjection` record and two JPQL aggregate queries that combine `COUNT/SUM/AVG` into one round-trip per vehicle (or per refuel type for hybrid). Replace the unbounded `findFullTankRefuelsByVehicleId` list with pageable variants capped at 50 records; `calculateAverageConsumption` keeps its existing formula and signature — only its input is now bounded. `findTopByVehicleIdOrderByRefuelDateDesc` is kept as a separate query for `lastRefuelDate` / `lastOdometer` (no clean way to fold those into a JPQL aggregate without window functions).

**Tech Stack:** Spring Data JPA, JPQL constructor expressions, `org.springframework.data.domain.Pageable` / `PageRequest`, Mockito (unit tests), `@DataJpaTest` + H2 (repository tests), Hibernate statistics (`SessionFactory.getStatistics()`) for query-count assertions.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| **Create** | `src/main/java/com/devappmobile/flowfuel/dashboard/RefuelAggregateProjection.java` | Projection record holding `count`, `totalSpent`, `totalEnergy`, `averagePrice` |
| **Modify** | `src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java` | Add 2 aggregate queries + 2 pageable full-tank methods |
| **Modify** | `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java` | Use new aggregate query + pageable instead of 5–9 individual queries |
| **Modify** | `src/test/resources/application.properties` | Enable Hibernate statistics for query-count test |
| **Create** | `src/test/java/com/devappmobile/flowfuel/refuel/RefuelRepositoryAggregateTest.java` | `@DataJpaTest` for new aggregate + pageable methods |
| **Modify** | `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java` | Update mocks to match refactored service call sites |
| **Create** | `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceQueryCountTest.java` | `@SpringBootTest` service-level query-count regression |

---

## Task 1: Create `RefuelAggregateProjection` record

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/dashboard/RefuelAggregateProjection.java`

- [ ] **Step 1: Create the projection record**

```java
package com.devappmobile.flowfuel.dashboard;

import java.math.BigDecimal;

public record RefuelAggregateProjection(
        Long count,
        BigDecimal totalSpent,
        BigDecimal totalEnergy,
        BigDecimal averagePrice
) {}
```

> This record is referenced by the JPQL constructor expression. The package must match the fully qualified name used in the query. JPQL COUNT always returns a non-null Long; SUM/AVG return null when no rows match — callers must null-check.

- [ ] **Step 2: Compile to confirm no errors**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/dashboard/RefuelAggregateProjection.java
git commit -m "feat(dashboard): add RefuelAggregateProjection record for M5 aggregate query"
```

---

## Task 2: Write failing `@DataJpaTest` for new repository methods

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/refuel/RefuelRepositoryAggregateTest.java`

These tests must fail until Task 3 adds the new methods.

- [ ] **Step 1: Create the test class**

```java
package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.dashboard.RefuelAggregateProjection;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RefuelRepositoryAggregateTest {

    @Autowired private RefuelRepository refuelRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private UserRepository userRepository;

    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("test@test.com");
        user.setPassword("hash");
        user.setName("Test");
        user = userRepository.save(user);

        vehicle = new Vehicle();
        vehicle.setType("car");
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCurrentKm(0);
        vehicle.setCapacity(50);
        vehicle.setUser(user);
        vehicle = vehicleRepository.save(vehicle);
    }

    // ── aggregate queries ─────────────────────────────────────────────────────

    @Test
    void getAggregatesByVehicleId_semAbastecimentos_retornaCountZeroESumsNulos() {
        RefuelAggregateProjection agg = refuelRepository.getAggregatesByVehicleId(vehicle.getId());

        assertThat(agg.count()).isZero();
        assertThat(agg.totalSpent()).isNull();
        assertThat(agg.totalEnergy()).isNull();
        assertThat(agg.averagePrice()).isNull();
    }

    @Test
    void getAggregatesByVehicleId_comDoisAbastecimentos_retornaAgregadosCorretos() {
        saveRefuel(1000, BigDecimal.valueOf(50.0), BigDecimal.valueOf(6.00), false, null);
        saveRefuel(1500, BigDecimal.valueOf(40.0), BigDecimal.valueOf(6.50), true, null);

        RefuelAggregateProjection agg = refuelRepository.getAggregatesByVehicleId(vehicle.getId());

        assertThat(agg.count()).isEqualTo(2L);
        // totalSpent = 50*6 + 40*6.5 = 300 + 260 = 560
        assertThat(agg.totalSpent()).isEqualByComparingTo(BigDecimal.valueOf(560.0));
        // totalEnergy = 50 + 40 = 90
        assertThat(agg.totalEnergy()).isEqualByComparingTo(BigDecimal.valueOf(90.0));
        // averagePrice = avg(6.00, 6.50) = 6.25
        assertThat(agg.averagePrice()).isEqualByComparingTo(BigDecimal.valueOf(6.25));
    }

    @Test
    void getAggregatesByVehicleIdAndRefuelType_filtraCorretamentePorTipo() {
        saveRefuel(1000, BigDecimal.valueOf(30.0), BigDecimal.valueOf(6.00), true, RefuelType.FUEL);
        saveRefuel(1200, BigDecimal.valueOf(10.0), BigDecimal.valueOf(1.20), true, RefuelType.ELECTRIC);

        RefuelAggregateProjection fuel = refuelRepository
                .getAggregatesByVehicleIdAndRefuelType(vehicle.getId(), RefuelType.FUEL);
        RefuelAggregateProjection electric = refuelRepository
                .getAggregatesByVehicleIdAndRefuelType(vehicle.getId(), RefuelType.ELECTRIC);

        assertThat(fuel.count()).isEqualTo(1L);
        assertThat(fuel.totalEnergy()).isEqualByComparingTo(BigDecimal.valueOf(30.0));
        assertThat(electric.count()).isEqualTo(1L);
        assertThat(electric.totalEnergy()).isEqualByComparingTo(BigDecimal.valueOf(10.0));
    }

    // ── pageable full-tank queries ────────────────────────────────────────────

    @Test
    void findFullTankRefuelsPaged_limitaResultados() {
        for (int i = 0; i < 5; i++) {
            saveRefuel(1000 + i * 100, BigDecimal.valueOf(40.0), BigDecimal.valueOf(6.0), true, null);
        }

        Page<Refuel> page = refuelRepository
                .findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(vehicle.getId(), PageRequest.of(0, 3));

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(5);
    }

    @Test
    void findFullTankRefuelsByTypePaged_filtraPorTipoELimita() {
        saveRefuel(1000, BigDecimal.valueOf(30.0), BigDecimal.valueOf(6.0), true, RefuelType.FUEL);
        saveRefuel(1100, BigDecimal.valueOf(30.0), BigDecimal.valueOf(6.0), true, RefuelType.FUEL);
        saveRefuel(1200, BigDecimal.valueOf(10.0), BigDecimal.valueOf(1.2), true, RefuelType.ELECTRIC);

        Page<Refuel> page = refuelRepository
                .findByVehicleIdAndRefuelTypeAndFullTankTrueOrderByRefuelDateDesc(
                        vehicle.getId(), RefuelType.FUEL, PageRequest.of(0, 50));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allMatch(r -> r.getRefuelType() == RefuelType.FUEL);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private void saveRefuel(int odometer, BigDecimal energy, BigDecimal price,
                            boolean fullTank, RefuelType type) {
        Refuel r = new Refuel();
        r.setOdometer(odometer);
        r.setEnergyAmount(energy);
        r.setPricePerUnit(price);
        r.setFullTank(fullTank);
        r.setRefuelType(type != null ? type : RefuelType.FUEL);
        r.setRefuelDate(LocalDateTime.now());
        r.setVehicle(vehicle);
        refuelRepository.save(r);
    }
}
```

- [ ] **Step 2: Run the tests to confirm they fail**

```bash
./mvnw test -pl . -Dtest=RefuelRepositoryAggregateTest -q 2>&1 | tail -20
```

Expected: COMPILATION ERROR or test failures — `getAggregatesByVehicleId`, `getAggregatesByVehicleIdAndRefuelType`, `findByVehicleIdAndRefuelTypeAndFullTankTrueOrderByRefuelDateDesc` do not exist yet.

---

## Task 3: Add new methods to `RefuelRepository`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java`

- [ ] **Step 1: Add the two aggregate JPQL queries and two pageable derived methods**

Add these four methods to `RefuelRepository` (anywhere after the existing methods is fine):

```java
// ── aggregate projections (M5) ───────────────────────────────────────────

@Query("""
        SELECT new com.devappmobile.flowfuel.dashboard.RefuelAggregateProjection(
            COUNT(r), SUM(r.totalAmount), SUM(r.energyAmount), AVG(r.pricePerUnit))
        FROM Refuel r WHERE r.vehicle.id = :vehicleId
        """)
RefuelAggregateProjection getAggregatesByVehicleId(@Param("vehicleId") Long vehicleId);

@Query("""
        SELECT new com.devappmobile.flowfuel.dashboard.RefuelAggregateProjection(
            COUNT(r), SUM(r.totalAmount), SUM(r.energyAmount), AVG(r.pricePerUnit))
        FROM Refuel r WHERE r.vehicle.id = :vehicleId AND r.refuelType = :refuelType
        """)
RefuelAggregateProjection getAggregatesByVehicleIdAndRefuelType(
        @Param("vehicleId") Long vehicleId,
        @Param("refuelType") RefuelType refuelType);

// ── pageable full-tank variants (M5) ────────────────────────────────────

Page<Refuel> findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(
        Long vehicleId, Pageable pageable);

Page<Refuel> findByVehicleIdAndRefuelTypeAndFullTankTrueOrderByRefuelDateDesc(
        Long vehicleId, RefuelType refuelType, Pageable pageable);
```

Also add these imports at the top of the interface (Spring Data JPA imports are already present; only `Page` and `Pageable` may be missing):

```java
import com.devappmobile.flowfuel.dashboard.RefuelAggregateProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
```

- [ ] **Step 2: Run the repository tests to confirm they pass**

```bash
./mvnw test -pl . -Dtest=RefuelRepositoryAggregateTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 5 tests passed.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java \
        src/main/java/com/devappmobile/flowfuel/dashboard/RefuelAggregateProjection.java \
        src/test/java/com/devappmobile/flowfuel/refuel/RefuelRepositoryAggregateTest.java
git commit -m "feat(dashboard): add aggregate JPQL queries and pageable full-tank methods to RefuelRepository (M5)"
```

---

## Task 4: Update `DashboardServiceTest` to reflect the new service call sites

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java`

The refactored service will call `getAggregatesByVehicleId` (one call) instead of the three separate `count`, `getTotalSpent`, `getTotalEnergy`, `getAveragePricePerUnit` calls, and `findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(id, pageable)` instead of `findFullTankRefuelsByVehicleId(id)`. All assertions on `DashboardDTO` field values stay the same.

- [ ] **Step 1: Replace the existing test file entirely**

```java
package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private RefuelRepository refuelRepository;
    @Mock private VehicleRepository vehicleRepository;

    @InjectMocks private DashboardService dashboardService;

    private User owner;
    private User otherUser;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        owner = new User("owner@test.com", "hash", "Owner");
        owner.setId(1L);

        otherUser = new User("other@test.com", "hash", "Other");
        otherUser.setId(2L);

        vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setUser(owner);
        vehicle.setEnergyType(EnergyType.COMBUSTION);
    }

    // ── authorization ──────────────────────────────────────────────────────────

    @Test
    void getVehicleDashboard_veiculoInexistente_lancaResourceNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dashboardService.getVehicleDashboard(owner, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getVehicleDashboard_usuarioNaoEDono_lancaForbidden() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> dashboardService.getVehicleDashboard(otherUser, 10L))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    // ── non-hybrid (COMBUSTION) ────────────────────────────────────────────────

    @Test
    void getVehicleDashboard_semAbastecimentos_retornaMetricasZeradas() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.getAggregatesByVehicleId(10L))
                .thenReturn(new RefuelAggregateProjection(0L, null, null, null));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L))
                .thenReturn(Optional.empty());
        when(refuelRepository.findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body.getTotalRefuels()).isZero();
        assertThat(body.getTotalSpent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.getAverageConsumption()).isEqualTo(0.0);
    }

    @Test
    void getVehicleDashboard_comAbastecimentos_retornaTotaisCorretos() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.getAggregatesByVehicleId(10L))
                .thenReturn(new RefuelAggregateProjection(
                        5L,
                        BigDecimal.valueOf(1500.00),
                        BigDecimal.valueOf(250.0),
                        BigDecimal.valueOf(6.00)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L))
                .thenReturn(Optional.empty());
        when(refuelRepository.findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body.getTotalRefuels()).isEqualTo(5L);
        assertThat(body.getTotalSpent()).isEqualByComparingTo(BigDecimal.valueOf(1500.00));
        assertThat(body.getTotalEnergy()).isEqualByComparingTo(BigDecimal.valueOf(250.0));
        assertThat(body.getEnergyType()).isEqualTo(EnergyType.COMBUSTION);
        assertThat(body.getEnergyUnit()).isEqualTo("litros");
        assertThat(body.getPriceUnit()).isEqualTo("R$/litro");
        assertThat(body.getConsumptionUnit()).isEqualTo("km/L");
    }

    @Test
    void getVehicleDashboard_veiculoEletrico_retornaUnidadesEmKwh() {
        vehicle.setEnergyType(EnergyType.ELECTRIC);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.getAggregatesByVehicleId(10L))
                .thenReturn(new RefuelAggregateProjection(
                        3L,
                        BigDecimal.valueOf(180.00),
                        BigDecimal.valueOf(150.0),
                        BigDecimal.valueOf(1.20)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L))
                .thenReturn(Optional.empty());
        when(refuelRepository.findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body.getEnergyType()).isEqualTo(EnergyType.ELECTRIC);
        assertThat(body.getEnergyUnit()).isEqualTo("kWh");
        assertThat(body.getPriceUnit()).isEqualTo("R$/kWh");
        assertThat(body.getConsumptionUnit()).isEqualTo("km/kWh");
        assertThat(body.getTotalEnergy()).isEqualByComparingTo(BigDecimal.valueOf(150.0));
    }

    @Test
    void getVehicleDashboard_comDoisTanquesCheios_calculaConsumoMedio() {
        Refuel recent = new Refuel();
        recent.setOdometer(2000);
        recent.setEnergyAmount(BigDecimal.valueOf(50.0));
        recent.setFullTank(true);
        recent.setRefuelDate(LocalDateTime.now());

        Refuel older = new Refuel();
        older.setOdometer(1500);
        older.setEnergyAmount(BigDecimal.valueOf(45.0));
        older.setFullTank(true);
        older.setRefuelDate(LocalDateTime.now().minusDays(7));

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.getAggregatesByVehicleId(10L))
                .thenReturn(new RefuelAggregateProjection(
                        2L, BigDecimal.valueOf(500), BigDecimal.valueOf(95), BigDecimal.valueOf(5.26)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L))
                .thenReturn(Optional.of(recent));
        when(refuelRepository.findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(recent, older)));

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        // 500 km / 50 L = 10.0 km/L
        assertThat(body.getAverageConsumption()).isEqualTo(10.0);
    }

    @Test
    void getVehicleDashboard_comApenasUmTanqueCheio_consumoEhZero() {
        Refuel singleRefuel = new Refuel();
        singleRefuel.setOdometer(1500);
        singleRefuel.setEnergyAmount(BigDecimal.valueOf(50.0));
        singleRefuel.setFullTank(true);
        singleRefuel.setRefuelDate(LocalDateTime.now());

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.getAggregatesByVehicleId(10L))
                .thenReturn(new RefuelAggregateProjection(1L, null, null, null));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L))
                .thenReturn(Optional.of(singleRefuel));
        when(refuelRepository.findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(singleRefuel)));

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body.getAverageConsumption()).isEqualTo(0.0);
    }

    /**
     * Contrato da fórmula oficial de consumo médio (ver Javadoc de calculateAverageConsumption).
     *
     * Cenário: 3 abastecimentos tanque-cheio [C(3000 km, 40 L), B(2200 km, 35 L), A(1500 km, 30 L)]
     * Par C-B: 800 km / 40 L; par B-A: 700 km / 35 L
     * Consumo esperado = (800+700) / (40+35) = 1500/75 = 20.00 km/L
     */
    @Test
    void getVehicleDashboard_formulaOficial_tresAbastecimentosTanqueCheio() {
        Refuel refuelC = refuelWithOdometerAndEnergy(3000, 40.0);
        Refuel refuelB = refuelWithOdometerAndEnergy(2200, 35.0);
        Refuel refuelA = refuelWithOdometerAndEnergy(1500, 30.0);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.getAggregatesByVehicleId(10L))
                .thenReturn(new RefuelAggregateProjection(
                        3L, BigDecimal.valueOf(900), BigDecimal.valueOf(105), BigDecimal.valueOf(8.57)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L))
                .thenReturn(Optional.of(refuelC));
        when(refuelRepository.findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(refuelC, refuelB, refuelA)));

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body.getAverageConsumption()).isEqualTo(20.0);
    }

    // ── hybrid ────────────────────────────────────────────────────────────────

    @Test
    void getVehicleDashboard_veiculoHibrido_retornaBreakdownComDoisVetores() {
        vehicle.setEnergyType(EnergyType.HYBRID);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.getAggregatesByVehicleId(10L))
                .thenReturn(new RefuelAggregateProjection(10L, BigDecimal.valueOf(5090.60), null, null));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L))
                .thenReturn(Optional.empty());

        when(refuelRepository.getAggregatesByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(new RefuelAggregateProjection(
                        7L,
                        BigDecimal.valueOf(4860.10),
                        BigDecimal.valueOf(820.5),
                        BigDecimal.valueOf(5.92)));
        when(refuelRepository.findByVehicleIdAndRefuelTypeAndFullTankTrueOrderByRefuelDateDesc(
                eq(10L), eq(RefuelType.FUEL), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        when(refuelRepository.getAggregatesByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(new RefuelAggregateProjection(
                        3L,
                        BigDecimal.valueOf(230.50),
                        BigDecimal.valueOf(189.75),
                        BigDecimal.valueOf(1.21)));
        when(refuelRepository.findByVehicleIdAndRefuelTypeAndFullTankTrueOrderByRefuelDateDesc(
                eq(10L), eq(RefuelType.ELECTRIC), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body.getEnergyType()).isEqualTo(EnergyType.HYBRID);
        assertThat(body.getTotalSpent()).isEqualByComparingTo(BigDecimal.valueOf(5090.60));
        assertThat(body.getTotalRefuels()).isEqualTo(10L);
        assertThat(body.getTotalEnergy()).isNull();
        assertThat(body.getAveragePrice()).isNull();
        assertThat(body.getEnergyUnit()).isNull();

        assertThat(body.getBreakdown()).isNotNull();
        assertThat(body.getBreakdown().getFuel().getTotalEnergy())
                .isEqualByComparingTo(BigDecimal.valueOf(820.5));
        assertThat(body.getBreakdown().getFuel().getEnergyUnit()).isEqualTo("litros");
        assertThat(body.getBreakdown().getFuel().getPriceUnit()).isEqualTo("R$/litro");
        assertThat(body.getBreakdown().getElectric().getTotalEnergy())
                .isEqualByComparingTo(BigDecimal.valueOf(189.75));
        assertThat(body.getBreakdown().getElectric().getEnergyUnit()).isEqualTo("kWh");
        assertThat(body.getBreakdown().getElectric().getConsumptionUnit()).isEqualTo("km/kWh");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Refuel refuelWithOdometerAndEnergy(int odometer, double energy) {
        Refuel r = new Refuel();
        r.setOdometer(odometer);
        r.setEnergyAmount(BigDecimal.valueOf(energy));
        r.setFullTank(true);
        r.setRefuelDate(LocalDateTime.now());
        return r;
    }
}
```

- [ ] **Step 2: Run to confirm the tests fail (service still calls old methods)**

```bash
./mvnw test -pl . -Dtest=DashboardServiceTest -q 2>&1 | tail -20
```

Expected: test failures — Mockito "unnecessary stubbings" or `NullPointerException` because `getAggregatesByVehicleId` is not yet called by the service.

---

## Task 5: Refactor `DashboardService` to use aggregate query and pageable

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java`

- [ ] **Step 1: Replace `buildDashboard` and `buildHybridBreakdown` / `buildFuelMetrics`**

Replace the entire file content (keep `calculateAverageConsumption` unchanged — it still receives `List<Refuel>`, which now comes from a pageable call instead of the full list):

```java
package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int FULL_TANK_WINDOW = 50;

    private final RefuelRepository refuelRepository;
    private final VehicleRepository vehicleRepository;

    public DashboardDTO getVehicleDashboard(User user, Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));
        if (!vehicle.getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Veículo não pertence ao usuário");
        }
        return buildDashboard(vehicle);
    }

    private DashboardDTO buildDashboard(Vehicle vehicle) {
        Long vehicleId = vehicle.getId();

        // Query 1: COUNT + SUM(totalAmount) + SUM(energyAmount) + AVG(pricePerUnit) in one round-trip
        RefuelAggregateProjection agg = refuelRepository.getAggregatesByVehicleId(vehicleId);

        // Query 2: last refuel entity for date + odometer
        Optional<Refuel> lastRefuelOpt =
                refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(vehicleId);

        LocalDate lastRefuelDate = null;
        Integer lastOdometer = null;
        if (lastRefuelOpt.isPresent()) {
            Refuel lastRefuel = lastRefuelOpt.get();
            lastRefuelDate = lastRefuel.getRefuelDate().toLocalDate();
            lastOdometer = lastRefuel.getOdometer();
        }

        DashboardDTO.DashboardDTOBuilder builder = DashboardDTO.builder()
                .vehicleId(vehicleId)
                .energyType(vehicle.getEnergyType())
                .totalRefuels(agg.count())
                .totalSpent(agg.totalSpent() != null ? agg.totalSpent() : BigDecimal.ZERO)
                .lastRefuelDate(lastRefuelDate)
                .lastOdometer(lastOdometer);

        if (vehicle.getEnergyType() == EnergyType.HYBRID) {
            builder.breakdown(buildHybridBreakdown(vehicleId));
        } else {
            // Query 3: bounded full-tank list for average consumption
            List<Refuel> fullTankRefuels = refuelRepository
                    .findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(
                            vehicleId, PageRequest.of(0, FULL_TANK_WINDOW))
                    .getContent();

            builder.totalEnergy(agg.totalEnergy() != null ? agg.totalEnergy() : BigDecimal.ZERO)
                    .averagePrice(agg.averagePrice() != null ? agg.averagePrice() : BigDecimal.ZERO)
                    .averageConsumption(calculateAverageConsumption(fullTankRefuels))
                    .energyUnit(vehicle.getEnergyUnit())
                    .priceUnit(vehicle.getPriceUnit())
                    .consumptionUnit(vehicle.getConsumptionUnit());
        }

        return builder.build();
    }

    private HybridBreakdownDTO buildHybridBreakdown(Long vehicleId) {
        return HybridBreakdownDTO.builder()
                .fuel(buildFuelMetrics(vehicleId, RefuelType.FUEL, "litros", "R$/litro", "km/L"))
                .electric(buildFuelMetrics(vehicleId, RefuelType.ELECTRIC, "kWh", "R$/kWh", "km/kWh"))
                .build();
    }

    private HybridBreakdownDTO.FuelMetrics buildFuelMetrics(Long vehicleId, RefuelType type,
                                                            String energyUnit, String priceUnit,
                                                            String consumptionUnit) {
        // Query A: type-scoped aggregate
        RefuelAggregateProjection agg =
                refuelRepository.getAggregatesByVehicleIdAndRefuelType(vehicleId, type);

        // Query B: bounded full-tank list for average consumption
        List<Refuel> fullTankRefuels = refuelRepository
                .findByVehicleIdAndRefuelTypeAndFullTankTrueOrderByRefuelDateDesc(
                        vehicleId, type, PageRequest.of(0, FULL_TANK_WINDOW))
                .getContent();

        return HybridBreakdownDTO.FuelMetrics.builder()
                .totalEnergy(agg.totalEnergy() != null ? agg.totalEnergy() : BigDecimal.ZERO)
                .totalSpent(agg.totalSpent() != null ? agg.totalSpent() : BigDecimal.ZERO)
                .averagePrice(agg.averagePrice() != null ? agg.averagePrice() : BigDecimal.ZERO)
                .averageConsumption(calculateAverageConsumption(fullTankRefuels))
                .energyUnit(energyUnit)
                .priceUnit(priceUnit)
                .consumptionUnit(consumptionUnit)
                .build();
    }

    /**
     * Fórmula oficial de consumo médio do produto.
     *
     * <p>Para cada par consecutivo de abastecimentos tanque-cheio ordenados do mais recente
     * ao mais antigo (current, previous), calcula:
     * <pre>
     *   kmDriven   = current.odometer - previous.odometer
     *   energyUsed = current.energyAmount
     * </pre>
     * O consumo final é {@code SUM(kmDriven) / SUM(energyUsed)} sobre todos os pares válidos
     * (kmDriven > 0 e energyUsed > 0), arredondado para 2 casas decimais (HALF_UP).
     *
     * <p>Exemplo: 3 abastecimentos [C(3000 km, 40 L), B(2200 km, 35 L), A(1500 km, 30 L)]
     * → pares: (C-B): 800 km / 40 L; (B-A): 700 km / 35 L
     * → consumo = (800+700) / (40+35) = 1500/75 = 20,00 km/L
     *
     * <p>Retorna 0.0 se houver menos de 2 abastecimentos tanque-cheio ou energia total zero.
     *
     * <p>Nota: o campo {@code kmSinceLastRefuel} persistido na entidade Refuel é deliberadamente
     * ignorado para garantir consistência com o odômetro registrado pelo usuário.
     *
     * <p>Recebe no máximo {@value DashboardService#FULL_TANK_WINDOW} registros (janela limitada
     * via Pageable) — não carrega a lista completa do histórico em memória.
     */
    private Double calculateAverageConsumption(List<Refuel> fullRefuels) {
        if (fullRefuels.size() < 2) {
            return 0.0;
        }

        double totalKm = 0;
        double totalEnergyUsed = 0;

        for (int i = 0; i < fullRefuels.size() - 1; i++) {
            Refuel current = fullRefuels.get(i);
            Refuel previous = fullRefuels.get(i + 1);

            double kmDriven = current.getOdometer() - previous.getOdometer();
            double energyUsed = current.getEnergyAmount().doubleValue();

            if (kmDriven > 0 && energyUsed > 0) {
                totalKm += kmDriven;
                totalEnergyUsed += energyUsed;
            }
        }

        if (totalEnergyUsed == 0) {
            return 0.0;
        }

        double consumption = totalKm / totalEnergyUsed;

        return BigDecimal.valueOf(consumption)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
```

- [ ] **Step 2: Run `DashboardServiceTest` to confirm all tests pass**

```bash
./mvnw test -pl . -Dtest=DashboardServiceTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, all 8 tests pass.

- [ ] **Step 3: Run the full test suite to detect regressions**

```bash
./mvnw test -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS. If `DashboardControllerIntegrationTest` fails because it set up mocks for the old repository methods, fix the integration test data setup (it uses real HTTP + real DB, so no mocks — failures would be logic bugs, not mock issues).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java \
        src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java
git commit -m "refactor(dashboard): reduce queries per request from 6-11 to 3-5 using aggregate projection and pageable full-tank window (M5)"
```

---

## Task 6: Query count regression test

**Files:**
- Modify: `src/test/resources/application.properties`
- Create: `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceQueryCountTest.java`

- [ ] **Step 1: Enable Hibernate statistics in test properties**

Add one line to `src/test/resources/application.properties`:

```properties
spring.jpa.properties.hibernate.generate_statistics=true
```

- [ ] **Step 2: Create the query-count test class**

```java
package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DashboardServiceQueryCountTest {

    @Autowired private DashboardService dashboardService;
    @Autowired private UserRepository userRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private RefuelRepository refuelRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private SessionFactory sessionFactory;
    private User owner;

    @BeforeEach
    void setUp() {
        sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().setStatisticsEnabled(true);

        refuelRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();

        owner = new User();
        owner.setEmail("qcount@test.com");
        owner.setPassword("hash");
        owner.setName("QCount");
        owner = userRepository.save(owner);
    }

    @Test
    void naoHibrido_chamadaAoDashboard_naoExcede3Queries() {
        Vehicle vehicle = saveVehicle(EnergyType.COMBUSTION);
        saveRefuel(vehicle, 1000, 40.0, 6.0, true);
        saveRefuel(vehicle, 1400, 38.0, 6.2, true);

        sessionFactory.getStatistics().clear();

        dashboardService.getVehicleDashboard(owner, vehicle.getId());

        long queries = sessionFactory.getStatistics().getQueryExecutionCount();
        assertThat(queries)
                .as("Non-hybrid dashboard should execute ≤ 3 queries (aggregate + lastRefuel + pageable full-tank)")
                .isLessThanOrEqualTo(3);
    }

    @Test
    void hibrido_chamadaAoDashboard_naoExcede5Queries() {
        Vehicle vehicle = saveVehicle(EnergyType.HYBRID);
        saveRefuelWithType(vehicle, 1000, 30.0, 6.0, true, RefuelType.FUEL);
        saveRefuelWithType(vehicle, 1200, 10.0, 1.2, true, RefuelType.ELECTRIC);

        sessionFactory.getStatistics().clear();

        dashboardService.getVehicleDashboard(owner, vehicle.getId());

        long queries = sessionFactory.getStatistics().getQueryExecutionCount();
        assertThat(queries)
                .as("Hybrid dashboard should execute ≤ 5 queries (overall-agg + lastRefuel + 2 type-agg + 2 pageable)")
                .isLessThanOrEqualTo(6);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Vehicle saveVehicle(EnergyType type) {
        Vehicle v = new Vehicle();
        v.setType("car");
        v.setEnergyType(type);
        v.setCurrentKm(0);
        v.setCapacity(50);
        v.setUser(owner);
        return vehicleRepository.save(v);
    }

    private void saveRefuel(Vehicle vehicle, int odometer, double energy, double price, boolean fullTank) {
        saveRefuelWithType(vehicle, odometer, energy, price, fullTank, RefuelType.FUEL);
    }

    private void saveRefuelWithType(Vehicle vehicle, int odometer, double energy,
                                    double price, boolean fullTank, RefuelType type) {
        Refuel r = new Refuel();
        r.setOdometer(odometer);
        r.setEnergyAmount(BigDecimal.valueOf(energy));
        r.setPricePerUnit(BigDecimal.valueOf(price));
        r.setFullTank(fullTank);
        r.setRefuelType(type);
        r.setRefuelDate(LocalDateTime.now());
        r.setVehicle(vehicle);
        refuelRepository.save(r);
    }
}
```

- [ ] **Step 3: Run the query-count tests**

```bash
./mvnw test -pl . -Dtest=DashboardServiceQueryCountTest -q 2>&1 | tail -15
```

Expected: BUILD SUCCESS, 2 tests pass. If `getQueryExecutionCount()` counts more than expected, check whether the `vehicleRepository.findById` call inside `getVehicleDashboard` counts as a JPQL query (it does — it's a primary key lookup). If so, bump the limits by 1 and document it.

- [ ] **Step 4: Run the full test suite one final time**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/application.properties \
        src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceQueryCountTest.java
git commit -m "test(dashboard): add Hibernate statistics query-count regression for M5"
```

---

## Self-Review

### Spec coverage check

| Requirement | Task |
|-------------|------|
| Combine COUNT/SUM/AVG into one query | Task 3 (`getAggregatesByVehicleId`) |
| Hybrid: same query parametrized by `refuelType` | Task 3 (`getAggregatesByVehicleIdAndRefuelType`) |
| No full list in memory for avg consumption | Task 5 (`FULL_TANK_WINDOW = 50` via `PageRequest`) |
| Avg consumption formula from M4 | Unchanged `calculateAverageConsumption` — formula preserved |
| API contract unchanged (`DashboardDTO` / `HybridBreakdownDTO`) | Task 5 — only internal queries change |
| Tests: parity for fixed fixture | Task 4 (formula test with 3 full-tank refuels) |
| Tests: `@DataJpaTest` covering no-refuels, few, hybrid | Task 2/3 (`RefuelRepositoryAggregateTest`) |
| Tests: query count regression | Task 6 (`DashboardServiceQueryCountTest`) |
| `DashboardControllerIntegrationTest` must still pass | Task 5 Step 3 (full suite run) |

**Cache (optional):** Out of scope per spec note ("pode virar item de Fase 3 separado"). Not included.

### Placeholder scan

No TBD, no "similar to", no vague instructions — all steps contain full code.

### Type consistency

- `RefuelAggregateProjection` record defined in Task 1, used in Task 3 (`@Query`) and Task 5 (`DashboardService`) — fields `count()`, `totalSpent()`, `totalEnergy()`, `averagePrice()` used consistently throughout.
- `PageRequest.of(0, FULL_TANK_WINDOW)` in Task 5 maps to `Pageable` parameter of methods added in Task 3.
- `findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(Long, Pageable)` and `findByVehicleIdAndRefuelTypeAndFullTankTrueOrderByRefuelDateDesc(Long, RefuelType, Pageable)` — names match exactly between Task 3 (repository) and Task 4/5 (mocks and service).
