# Localização do posto no abastecimento (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `POST/PUT /refuels` accept and return an optional snapshot of the station where the refuel happened (`stationName`, `stationAddress`, `stationLatitude`, `stationLongitude`), persisted directly on `Refuel` (no FK — there's no persisted `Station` entity to point to).

**Architecture:** Four new nullable columns on `refuels` (Flyway migration), mirrored as plain fields on the `Refuel` entity and on both request/response DTOs, set unconditionally in `RefuelService.createRefuel`/`updateRefuel`.

**Tech Stack:** Spring Boot, JPA/Hibernate, Flyway, JUnit 5 + Mockito + AssertJ (existing test stack — H2 in-memory with `ddl-auto=create-drop` for tests, so the new columns exist automatically without running the Flyway migration in tests).

---

## File Structure

- Create `src/main/resources/db/migration/V14__refuel_station.sql` — new columns (production/dev schema only; tests use Hibernate-generated schema from the entity).
- Modify `src/main/java/com/devappmobile/flowfuel/refuel/Refuel.java` — 4 new fields.
- Modify `src/main/java/com/devappmobile/flowfuel/refuel/RefuelRequestDTO.java` — 4 new optional fields.
- Modify `src/main/java/com/devappmobile/flowfuel/refuel/RefuelResponseDTO.java` — 4 new fields + mapping in `from()`.
- Modify `src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java` — set the 4 fields in `createRefuel` and `updateRefuel`.
- Modify `src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTest.java` — new tests for the field-setting behavior.

---

### Task 1: Migration

**Files:**
- Create: `src/main/resources/db/migration/V14__refuel_station.sql`

- [ ] **Step 1: Create the migration file**

```sql
ALTER TABLE refuels
    ADD COLUMN station_name VARCHAR(255),
    ADD COLUMN station_address VARCHAR(500),
    ADD COLUMN station_latitude DOUBLE PRECISION,
    ADD COLUMN station_longitude DOUBLE PRECISION;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V14__refuel_station.sql
git commit -m "feat(db): add station snapshot columns to refuels"
```

---

### Task 2: `Refuel.java` entity fields

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/refuel/Refuel.java`

- [ ] **Step 1: Add the 4 fields**

Find:

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "refuel_type", nullable = false, length = 16)
    private RefuelType refuelType;

    @JsonIgnore
    @ManyToOne
```

Replace with:

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "refuel_type", nullable = false, length = 16)
    private RefuelType refuelType;

    @Column(name = "station_name")
    private String stationName;

    @Column(name = "station_address")
    private String stationAddress;

    @Column(name = "station_latitude")
    private Double stationLatitude;

    @Column(name = "station_longitude")
    private Double stationLongitude;

    @JsonIgnore
    @ManyToOne
```

- [ ] **Step 2: Compile**

Run: `mvn -q compile`
Expected: build succeeds with no errors.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/refuel/Refuel.java
git commit -m "feat(refuel): add station snapshot fields to Refuel entity"
```

---

### Task 3: `RefuelRequestDTO.java` and `RefuelResponseDTO.java`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/refuel/RefuelRequestDTO.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/refuel/RefuelResponseDTO.java`

- [ ] **Step 1: Add fields to `RefuelRequestDTO`**

Find:

```java
    private Boolean fullTank = false;

    @Schema(description = "Tipo do abastecimento. Obrigatório para veículos HYBRID; inferido para COMBUSTION (FUEL) e ELECTRIC (ELECTRIC).",
            nullable = true, example = "FUEL")
    private RefuelType refuelType;
}
```

Replace with:

```java
    private Boolean fullTank = false;

    @Schema(description = "Tipo do abastecimento. Obrigatório para veículos HYBRID; inferido para COMBUSTION (FUEL) e ELECTRIC (ELECTRIC).",
            nullable = true, example = "FUEL")
    private RefuelType refuelType;

    @Schema(description = "Snapshot opcional do posto escolhido pelo usuário no momento do abastecimento.", nullable = true)
    private String stationName;

    @Schema(nullable = true)
    private String stationAddress;

    @Schema(nullable = true)
    private Double stationLatitude;

    @Schema(nullable = true)
    private Double stationLongitude;
}
```

- [ ] **Step 2: Add fields and mapping to `RefuelResponseDTO`**

Find:

```java
    private Boolean fullTank;
    private RefuelType refuelType;

    public static RefuelResponseDTO from(Refuel r) {
        return RefuelResponseDTO.builder()
                .id(r.getId())
                .vehicleId(r.getVehicle() != null ? r.getVehicle().getId() : null)
                .refuelDate(r.getRefuelDate())
                .odometer(r.getOdometer())
                .kmSinceLastRefuel(r.getKmSinceLastRefuel())
                .energyAmount(r.getEnergyAmount())
                .pricePerUnit(r.getPricePerUnit())
                .totalAmount(r.getTotalAmount())
                .fullTank(r.getFullTank())
                .refuelType(r.getRefuelType())
                .build();
    }
}
```

Replace with:

```java
    private Boolean fullTank;
    private RefuelType refuelType;
    private String stationName;
    private String stationAddress;
    private Double stationLatitude;
    private Double stationLongitude;

    public static RefuelResponseDTO from(Refuel r) {
        return RefuelResponseDTO.builder()
                .id(r.getId())
                .vehicleId(r.getVehicle() != null ? r.getVehicle().getId() : null)
                .refuelDate(r.getRefuelDate())
                .odometer(r.getOdometer())
                .kmSinceLastRefuel(r.getKmSinceLastRefuel())
                .energyAmount(r.getEnergyAmount())
                .pricePerUnit(r.getPricePerUnit())
                .totalAmount(r.getTotalAmount())
                .fullTank(r.getFullTank())
                .refuelType(r.getRefuelType())
                .stationName(r.getStationName())
                .stationAddress(r.getStationAddress())
                .stationLatitude(r.getStationLatitude())
                .stationLongitude(r.getStationLongitude())
                .build();
    }
}
```

- [ ] **Step 3: Compile**

Run: `mvn -q compile`
Expected: build succeeds with no errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/refuel/RefuelRequestDTO.java src/main/java/com/devappmobile/flowfuel/refuel/RefuelResponseDTO.java
git commit -m "feat(refuel): add station snapshot fields to request/response DTOs"
```

---

### Task 4: `RefuelService.java` — write the failing tests first, then implement

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTest.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java`

- [ ] **Step 1: Add a `createRefuel` test for station fields**

Find the `buildRequest` helper:

```java
    private RefuelRequestDTO buildRequest(int odometer, double energyAmount, double price) {
        RefuelRequestDTO dto = new RefuelRequestDTO();
        dto.setVehicleId(10L);
        dto.setOdometer(odometer);
        dto.setEnergyAmount(BigDecimal.valueOf(energyAmount));
        dto.setPricePerUnit(BigDecimal.valueOf(price));
        dto.setFullTank(true);
        return dto;
    }
```

Leave it as-is (other tests depend on it not sending station fields) and add a new test right after `createRefuel_dadosValidos_retornaRefuelComKmCalculado` (around line 95):

```java
    @Test
    void createRefuel_comPosto_gravaSnapshotDoPosto() {
        RefuelRequestDTO dto = buildRequest(1500, 40.0, 5.89);
        dto.setStationName("Posto Ipiranga");
        dto.setStationAddress("Rua Augusta, 100");
        dto.setStationLatitude(-23.55);
        dto.setStationLongitude(-46.63);

        Refuel saved = new Refuel();
        saved.setId(1L);
        saved.setOdometer(1500);
        saved.setKmSinceLastRefuel(500);
        saved.setEnergyAmount(BigDecimal.valueOf(40.0));
        saved.setPricePerUnit(BigDecimal.valueOf(5.89));
        saved.setVehicle(vehicle);
        saved.setStationName("Posto Ipiranga");
        saved.setStationAddress("Rua Augusta, 100");
        saved.setStationLatitude(-23.55);
        saved.setStationLongitude(-46.63);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.save(any(Refuel.class))).thenReturn(saved);
        when(vehicleRepository.save(any(Vehicle.class))).thenReturn(vehicle);

        RefuelResponseDTO response = refuelService.createRefuel(owner, dto);

        assertThat(response.getStationName()).isEqualTo("Posto Ipiranga");
        assertThat(response.getStationAddress()).isEqualTo("Rua Augusta, 100");
        assertThat(response.getStationLatitude()).isEqualTo(-23.55);
        assertThat(response.getStationLongitude()).isEqualTo(-46.63);
        verify(refuelRepository).save(argThat(r ->
                "Posto Ipiranga".equals(r.getStationName())
                        && "Rua Augusta, 100".equals(r.getStationAddress())
                        && Double.valueOf(-23.55).equals(r.getStationLatitude())
                        && Double.valueOf(-46.63).equals(r.getStationLongitude())
        ));
    }
```

- [ ] **Step 2: Run the new test to see it fail**

Run: `mvn -q test -Dtest=RefuelServiceTest#createRefuel_comPosto_gravaSnapshotDoPosto`
Expected: FAIL — compile error (`RefuelRequestDTO` doesn't have `setStationName` etc. yet is fine, they exist since Task 3; actual failure is the assertion, since `RefuelService.createRefuel` doesn't set these fields on the entity yet, so `saved`'s station fields are only present because the test manually put them on the mocked `saved` object — the `verify(refuelRepository).save(argThat(...)))` assertion is what fails, because the real `refuel` object built inside `createRefuel` never got `setStationName` called on it).

- [ ] **Step 3: Add an `updateRefuel` test for station fields**

Add after the `createRefuel_veiculoEletrico_aceitaPrecoNaFaixaEletrica` test (or anywhere in the class, before `getRefuelById_donoCorreto_retornaRefuel`):

```java
    @Test
    void updateRefuel_comPosto_atualizaSnapshotDoPosto() {
        Refuel existing = new Refuel();
        existing.setId(1L);
        existing.setOdometer(1500);
        existing.setEnergyAmount(BigDecimal.valueOf(40.0));
        existing.setPricePerUnit(BigDecimal.valueOf(5.89));
        existing.setRefuelType(RefuelType.FUEL);
        existing.setVehicle(vehicle);

        RefuelRequestDTO dto = new RefuelRequestDTO();
        dto.setStationName("Posto Shell");
        dto.setStationAddress("Av. Paulista, 900");
        dto.setStationLatitude(-23.56);
        dto.setStationLongitude(-46.65);

        when(refuelRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(refuelRepository.save(any(Refuel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RefuelResponseDTO response = refuelService.updateRefuel(owner, 1L, dto);

        assertThat(response.getStationName()).isEqualTo("Posto Shell");
        assertThat(response.getStationAddress()).isEqualTo("Av. Paulista, 900");
        assertThat(response.getStationLatitude()).isEqualTo(-23.56);
        assertThat(response.getStationLongitude()).isEqualTo(-46.65);
    }

    @Test
    void updateRefuel_semPostoNaRequisicao_removePostoExistente() {
        Refuel existing = new Refuel();
        existing.setId(1L);
        existing.setOdometer(1500);
        existing.setEnergyAmount(BigDecimal.valueOf(40.0));
        existing.setPricePerUnit(BigDecimal.valueOf(5.89));
        existing.setRefuelType(RefuelType.FUEL);
        existing.setVehicle(vehicle);
        existing.setStationName("Posto Antigo");

        RefuelRequestDTO dto = new RefuelRequestDTO();

        when(refuelRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(refuelRepository.save(any(Refuel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RefuelResponseDTO response = refuelService.updateRefuel(owner, 1L, dto);

        assertThat(response.getStationName()).isNull();
    }
```

- [ ] **Step 4: Run both new tests to see them fail**

Run: `mvn -q test -Dtest=RefuelServiceTest#updateRefuel_comPosto_atualizaSnapshotDoPosto+updateRefuel_semPostoNaRequisicao_removePostoExistente`
Expected: FAIL — `response.getStationName()` is `null` in the first test (should be `"Posto Shell"`), because `updateRefuel` doesn't touch station fields yet.

- [ ] **Step 5: Implement in `RefuelService.java`**

Find, in `createRefuel`:

```java
        refuel.setRefuelType(refuelType);
        refuel.setVehicle(vehicle);

        Refuel saved = refuelRepository.save(refuel);
```

Replace with:

```java
        refuel.setRefuelType(refuelType);
        refuel.setVehicle(vehicle);
        refuel.setStationName(request.getStationName());
        refuel.setStationAddress(request.getStationAddress());
        refuel.setStationLatitude(request.getStationLatitude());
        refuel.setStationLongitude(request.getStationLongitude());

        Refuel saved = refuelRepository.save(refuel);
```

Find, in `updateRefuel`:

```java
        if (request.getFullTank() != null) refuel.setFullTank(request.getFullTank());

        return RefuelResponseDTO.from(refuelRepository.save(refuel));
```

Replace with:

```java
        if (request.getFullTank() != null) refuel.setFullTank(request.getFullTank());

        refuel.setStationName(request.getStationName());
        refuel.setStationAddress(request.getStationAddress());
        refuel.setStationLatitude(request.getStationLatitude());
        refuel.setStationLongitude(request.getStationLongitude());

        return RefuelResponseDTO.from(refuelRepository.save(refuel));
```

Note this is deliberately unconditional (unlike `odometer`/`energyAmount`/`pricePerUnit`/`fullTank` above it, which only update `if != null`): the frontend always sends the current station state — including `null` when the user removed it — so the update must always overwrite, not skip on `null`.

- [ ] **Step 6: Run all 4 tests to see them pass**

Run: `mvn -q test -Dtest=RefuelServiceTest#createRefuel_comPosto_gravaSnapshotDoPosto+updateRefuel_comPosto_atualizaSnapshotDoPosto+updateRefuel_semPostoNaRequisicao_removePostoExistente`
Expected: PASS (3 tests, all green).

- [ ] **Step 7: Run the full test suite**

Run: `mvn -q test`
Expected: all tests pass, including the pre-existing `RefuelServiceTest`, `RefuelServiceTransactionalTest`, `RefuelControllerIntegrationTest`, `RefuelRepositoryAggregateTest` (none of them send station fields, so they exercise the `null`-in/`null`-out path, which is unaffected by this change).

- [ ] **Step 8: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTest.java src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java
git commit -m "feat(refuel): persist station snapshot on create/update"
```

---

### Task 5: Full build verification and push

**Files:** none (verification only)

- [ ] **Step 1: Run the full build**

Run: `mvn -q clean verify`
Expected: build succeeds, all tests pass.

- [ ] **Step 2: Push**

```bash
git push
```

Expected: CI (if configured) passes. This must be deployed before the frontend part of this feature goes live, since the frontend will start sending `stationName`/`stationAddress`/`stationLatitude`/`stationLongitude` in `POST/PUT /refuels` bodies — an older backend without these DTO fields would simply ignore them (Jackson default behavior), so ordering isn't strictly blocking, but deploying backend first avoids a window where the frontend saves a station and the backend silently drops it.
