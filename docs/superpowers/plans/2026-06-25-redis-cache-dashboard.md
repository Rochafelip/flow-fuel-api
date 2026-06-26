# Redis Cache (Dashboard + Veículo Ativo) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cache `GET /dashboard/vehicle/{vehicleId}` and `GET /vehicles/active` in Redis (TTL 5min, fail-open), with explicit eviction on writes that invalidate them, so reads don't recompute expensive aggregate queries on every request.

**Architecture:** Add `spring-boot-starter-data-redis` + `@EnableCaching` with a custom `RedisCacheManager` (5min TTL, JSON serialization) and a `CacheErrorHandler` that swallows Redis errors (fail-open, matching `RateLimitFilter`'s existing philosophy). Extract the expensive dashboard-calculation logic out of `DashboardService` into a new `DashboardCacheService` bean carrying the `@Cacheable` annotation (avoids the classic Spring self-invocation pitfall where `@Cacheable` on a method called from within the same class instance is silently skipped). Add `@CacheEvict`/`@Cacheable` to `RefuelService` and `VehicleService` at the exact write/read points identified in the spec.

**Tech Stack:** Spring Boot Cache abstraction (`spring-boot-starter-data-redis`), Redis (already provisioned for dev via `docker-compose.yml`), Testcontainers (integration tests), JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-25-redis-cache-dashboard-design.md`

---

### Task 1: Add `spring-boot-starter-data-redis` dependency + Redis connection property

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add the dependency**

In `pom.xml`, add this dependency right after the `spring-boot-starter-data-jpa` entry (around line 38):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-redis</artifactId>
		</dependency>
```

- [ ] **Step 2: Add the connection property**

In `src/main/resources/application.properties`, add this line near the existing `flowfuel.rate-limit.redis-url` property (around line 84):

```properties
spring.data.redis.url=${REDIS_URL:redis://localhost:6379}
```

This reuses the same `REDIS_URL` env var already used by rate-limiting — both features point at the same Redis instance, just through different client stacks (bucket4j+Lettuce manually for rate-limit, Spring Data Redis's autoconfigured `RedisConnectionFactory` for cache).

- [ ] **Step 3: Compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS — `spring-boot-starter-data-redis` pulls in Lettuce as its default client (already present as a direct dependency for rate-limiting, so no version conflict).

- [ ] **Step 4: Run the existing test suite to confirm no startup regression**

Run: `mvn -q test -Dtest=RateLimitFilterTest,RateLimitFilterIntegrationTest`
Expected: PASS — Lettuce connections are lazy, so adding the Redis cache starter must not break context startup even when no Redis is reachable in plain unit tests (H2 profile doesn't start a real Redis).

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties
git commit -m "feat: add spring-boot-starter-data-redis dependency and connection property"
```

---

### Task 2: `FailOpenCacheErrorHandler`

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/config/FailOpenCacheErrorHandler.java`
- Test: `src/test/java/com/devappmobile/flowfuel/config/FailOpenCacheErrorHandlerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FailOpenCacheErrorHandlerTest {

    private final FailOpenCacheErrorHandler handler = new FailOpenCacheErrorHandler();

    @Test
    void handleCacheGetError_naoPropagaExcecao() {
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("dashboard");

        assertThatNoException().isThrownBy(() ->
                handler.handleCacheGetError(new RuntimeException("Redis indisponivel"), cache, 1L));
    }

    @Test
    void handleCachePutError_naoPropagaExcecao() {
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("dashboard");

        assertThatNoException().isThrownBy(() ->
                handler.handleCachePutError(new RuntimeException("Redis indisponivel"), cache, 1L, "valor"));
    }

    @Test
    void handleCacheEvictError_naoPropagaExcecao() {
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("dashboard");

        assertThatNoException().isThrownBy(() ->
                handler.handleCacheEvictError(new RuntimeException("Redis indisponivel"), cache, 1L));
    }

    @Test
    void handleCacheClearError_naoPropagaExcecao() {
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("dashboard");

        assertThatNoException().isThrownBy(() ->
                handler.handleCacheClearError(new RuntimeException("Redis indisponivel"), cache));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=FailOpenCacheErrorHandlerTest`
Expected: FAIL — `FailOpenCacheErrorHandler` does not exist (compilation error)

- [ ] **Step 3: Write the implementation**

```java
package com.devappmobile.flowfuel.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Mesma filosofia fail-open do {@link RateLimitFilter}: se o Redis do cache
 * ficar indisponivel, a aplicacao deve continuar funcionando (sem cache, com
 * mais carga no Postgres) em vez de retornar erro ao cliente.
 */
public class FailOpenCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(FailOpenCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache indisponivel (get) cache={} key={} error={}",
                cache.getName(), key, exception.getMessage());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("Cache indisponivel (put) cache={} key={} error={}",
                cache.getName(), key, exception.getMessage());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache indisponivel (evict) cache={} key={} error={}",
                cache.getName(), key, exception.getMessage());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("Cache indisponivel (clear) cache={} error={}",
                cache.getName(), exception.getMessage());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=FailOpenCacheErrorHandlerTest`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/config/FailOpenCacheErrorHandler.java src/test/java/com/devappmobile/flowfuel/config/FailOpenCacheErrorHandlerTest.java
git commit -m "feat: add FailOpenCacheErrorHandler for fail-open cache behavior"
```

---

### Task 3: `CacheConfig` (RedisCacheManager + wire the error handler)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/config/CacheConfig.java`

- [ ] **Step 1: Write the configuration**

```java
package com.devappmobile.flowfuel.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CachingConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Cache de leitura para dashboard/veiculo-ativo (ver
 * docs/superpowers/specs/2026-06-25-redis-cache-dashboard-design.md). TTL de
 * 5 minutos como rede de seguranca; invalidacao primaria e via @CacheEvict
 * explicito nos pontos de escrita relevantes (RefuelService, VehicleService).
 *
 * <p>Implementa {@link CachingConfigurer} apenas para registrar o
 * {@link FailOpenCacheErrorHandler} — sem isso, uma falha do Redis em tempo
 * de execucao propagaria como excecao para o cliente, quebrando o
 * comportamento fail-open documentado no spec.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new FailOpenCacheErrorHandler();
    }
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Run the full test suite to confirm Spring context still loads everywhere**

Run: `mvn -q test -Dtest=DashboardControllerIntegrationTest,VehicleControllerIntegrationTest`
Expected: PASS — these `@SpringBootTest` classes load the full context, including the new `CacheConfig`; since H2 test profile has no real Redis but Lettuce connects lazily, context loading must still succeed.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/config/CacheConfig.java
git commit -m "feat: add CacheConfig with 5min TTL RedisCacheManager and fail-open error handler"
```

---

### Task 4: Extract `DashboardCacheService` (the `@Cacheable` boundary)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardCacheService.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java`
- Create: `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardCacheServiceTest.java`

**Why a separate class:** Spring's `@Cacheable` works via a proxy wrapping the bean. If `DashboardService.getVehicleDashboard(...)` called `this.buildDashboard(...)` directly (a "self-invocation"), the proxy would never see that call and caching would silently do nothing. Moving the cacheable logic into a different bean, invoked through its public Spring-managed proxy, avoids this pitfall entirely.

- [ ] **Step 1: Create `DashboardCacheService` with all the calculation logic moved from `DashboardService`**

```java
package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Calculo das metricas agregadas do dashboard de um veiculo — extraido de
 * {@link DashboardService} para que o cache (chave = vehicleId, TTL 5min,
 * fail-open) funcione via proxy Spring sem o problema de self-invocation.
 * A checagem de ownership permanece em {@code DashboardService}, fora desta
 * classe, e sempre executa antes desta chamada (cache hit ou miss).
 */
@Component
@RequiredArgsConstructor
public class DashboardCacheService {

    private final RefuelRepository refuelRepository;

    @Cacheable(cacheNames = "dashboard", key = "#vehicle.id")
    public DashboardDTO buildDashboard(Vehicle vehicle) {
        Long vehicleId = vehicle.getId();
        Long totalRefuels = refuelRepository.countByVehicleId(vehicleId);

        BigDecimal totalSpent = refuelRepository
                .getTotalSpentByVehicleId(vehicleId)
                .orElse(BigDecimal.ZERO);

        Optional<Refuel> lastRefuelOpt =
                refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(vehicleId);

        LocalDate lastRefuelDate = null;
        Integer lastOdometer = null;
        if (lastRefuelOpt.isPresent()) {
            Refuel lastRefuel = lastRefuelOpt.get();
            lastRefuelDate = lastRefuel.getRefuelDate().toLocalDate();
            lastOdometer = lastRefuel.getOdometer();
        }

        BigDecimal costPerKm = calculateCostPerKm(
                refuelRepository.findByVehicleIdOrderByOdometerDesc(vehicleId));

        DashboardDTO.DashboardDTOBuilder builder = DashboardDTO.builder()
                .vehicleId(vehicleId)
                .energyType(vehicle.getEnergyType())
                .totalRefuels(totalRefuels)
                .totalSpent(totalSpent)
                .costPerKm(costPerKm)
                .lastRefuelDate(lastRefuelDate)
                .lastOdometer(lastOdometer);

        if (vehicle.getEnergyType() == EnergyType.HYBRID) {
            builder.breakdown(buildHybridBreakdown(vehicleId));
        } else {
            BigDecimal totalEnergy = refuelRepository
                    .getTotalEnergyByVehicleId(vehicleId)
                    .orElse(BigDecimal.ZERO);
            BigDecimal averagePrice = refuelRepository
                    .getAveragePricePerUnitByVehicleId(vehicleId)
                    .orElse(BigDecimal.ZERO);
            Double averageConsumption = calculateAverageConsumption(
                    refuelRepository.findFullTankRefuelsByVehicleId(vehicleId));

            builder.totalEnergy(totalEnergy)
                    .averagePrice(averagePrice)
                    .averageConsumption(averageConsumption)
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
        BigDecimal totalEnergy = refuelRepository
                .getTotalEnergyByVehicleIdAndRefuelType(vehicleId, type)
                .orElse(BigDecimal.ZERO);
        BigDecimal totalSpent = refuelRepository
                .getTotalSpentByVehicleIdAndRefuelType(vehicleId, type)
                .orElse(BigDecimal.ZERO);
        BigDecimal averagePrice = refuelRepository
                .getAveragePricePerUnitByVehicleIdAndRefuelType(vehicleId, type)
                .orElse(BigDecimal.ZERO);
        Double averageConsumption = calculateAverageConsumption(
                refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(vehicleId, type));

        return HybridBreakdownDTO.FuelMetrics.builder()
                .totalEnergy(totalEnergy)
                .totalSpent(totalSpent)
                .averagePrice(averagePrice)
                .averageConsumption(averageConsumption)
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
     * <p>Retorna 0.0 se houver menos de 2 abastecimentos tanque-cheio ou energia total zero.
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

    /**
     * Custo médio por km rodado (R$/km), usando TODOS os abastecimentos (cheios ou
     * parciais) — diferente de {@code calculateAverageConsumption}, não exige tanque
     * cheio, pois todo valor pago entre dois abastecimentos custeou aquele trecho rodado.
     */
    private BigDecimal calculateCostPerKm(List<Refuel> refuelsOrderedByOdometerDesc) {
        if (refuelsOrderedByOdometerDesc.size() < 2) {
            return BigDecimal.ZERO;
        }

        double totalKm = 0;
        BigDecimal totalSpentOnSegments = BigDecimal.ZERO;

        for (int i = 0; i < refuelsOrderedByOdometerDesc.size() - 1; i++) {
            Refuel current = refuelsOrderedByOdometerDesc.get(i);
            Refuel previous = refuelsOrderedByOdometerDesc.get(i + 1);

            double kmDriven = current.getOdometer() - previous.getOdometer();

            if (kmDriven > 0) {
                totalKm += kmDriven;
                totalSpentOnSegments = totalSpentOnSegments.add(current.getTotalAmount());
            }
        }

        if (totalKm == 0) {
            return BigDecimal.ZERO;
        }

        return totalSpentOnSegments
                .divide(BigDecimal.valueOf(totalKm), 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 2: Replace `DashboardService` with a thin auth-check + delegate**

Replace the entire contents of `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java` with:

```java
package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final VehicleRepository vehicleRepository;
    private final AuthorizationHelper authorizationHelper;
    private final DashboardCacheService dashboardCacheService;

    public DashboardDTO getVehicleDashboard(User user, Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));
        authorizationHelper.ensureOwnsVehicle(user, vehicle);
        return dashboardCacheService.buildDashboard(vehicle);
    }
}
```

Note: ownership is always checked here, on every call, regardless of cache hit/miss in `DashboardCacheService` — the cache key is `vehicleId` (not `userId`), so this ordering is what prevents the cache from ever being usable as an authorization bypass.

- [ ] **Step 3: Replace `DashboardServiceTest` — keep only the auth-related tests, add a delegation test**

Replace the entire contents of `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java` with:

```java
package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private VehicleRepository vehicleRepository;
    @Mock private AuthorizationHelper authorizationHelper;
    @Mock private DashboardCacheService dashboardCacheService;

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

    @Test
    void getVehicleDashboard_veiculoInexistente_lancaResourceNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dashboardService.getVehicleDashboard(owner, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getVehicleDashboard_usuarioNaoEDono_lancaForbidden() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

        assertThatThrownBy(() -> dashboardService.getVehicleDashboard(otherUser, 10L))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void getVehicleDashboard_proprietarioValido_delegaParaDashboardCacheServiceAposAutorizar() {
        DashboardDTO expected = DashboardDTO.builder().vehicleId(10L).build();
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(dashboardCacheService.buildDashboard(vehicle)).thenReturn(expected);

        DashboardDTO result = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(result).isSameAs(expected);
        verify(authorizationHelper).ensureOwnsVehicle(owner, vehicle);
        verify(dashboardCacheService).buildDashboard(vehicle);
    }
}
```

- [ ] **Step 4: Create `DashboardCacheServiceTest` — the calculation tests, moved**

```java
package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardCacheServiceTest {

    @Mock private RefuelRepository refuelRepository;

    @InjectMocks private DashboardCacheService dashboardCacheService;

    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setEnergyType(EnergyType.COMBUSTION);
    }

    @Test
    void buildDashboard_semAbastecimentos_retornaMetricasZeradas() {
        when(refuelRepository.countByVehicleId(10L)).thenReturn(0L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of());

        DashboardDTO body = dashboardCacheService.buildDashboard(vehicle);

        assertThat(body).isNotNull();
        assertThat(body.getTotalRefuels()).isEqualTo(0L);
        assertThat(body.getTotalSpent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.getAverageConsumption()).isEqualTo(0.0);
    }

    @Test
    void buildDashboard_comAbastecimentos_retornaTotaisCorretos() {
        when(refuelRepository.countByVehicleId(10L)).thenReturn(5L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(1500.00)));
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(250.0)));
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(6.00)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of());

        DashboardDTO body = dashboardCacheService.buildDashboard(vehicle);

        assertThat(body).isNotNull();
        assertThat(body.getTotalRefuels()).isEqualTo(5L);
        assertThat(body.getTotalSpent()).isEqualByComparingTo(BigDecimal.valueOf(1500.00));
        assertThat(body.getTotalEnergy()).isEqualByComparingTo(BigDecimal.valueOf(250.0));
        assertThat(body.getEnergyType()).isEqualTo(EnergyType.COMBUSTION);
        assertThat(body.getEnergyUnit()).isEqualTo("litros");
        assertThat(body.getPriceUnit()).isEqualTo("R$/litro");
        assertThat(body.getConsumptionUnit()).isEqualTo("km/L");
    }

    @Test
    void buildDashboard_veiculoEletrico_retornaUnidadesEmKwh() {
        vehicle.setEnergyType(EnergyType.ELECTRIC);

        when(refuelRepository.countByVehicleId(10L)).thenReturn(3L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(180.00)));
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(150.0)));
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(1.20)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of());

        DashboardDTO body = dashboardCacheService.buildDashboard(vehicle);

        assertThat(body.getEnergyType()).isEqualTo(EnergyType.ELECTRIC);
        assertThat(body.getEnergyUnit()).isEqualTo("kWh");
        assertThat(body.getPriceUnit()).isEqualTo("R$/kWh");
        assertThat(body.getConsumptionUnit()).isEqualTo("km/kWh");
        assertThat(body.getTotalEnergy()).isEqualByComparingTo(BigDecimal.valueOf(150.0));
    }

    @Test
    void buildDashboard_comDoisTanquesCheios_calculaConsumoMedio() {
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

        when(refuelRepository.countByVehicleId(10L)).thenReturn(2L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(500)));
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(95)));
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(5.26)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.of(recent));
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of(recent, older));

        DashboardDTO body = dashboardCacheService.buildDashboard(vehicle);

        assertThat(body).isNotNull();
        assertThat(body.getAverageConsumption()).isEqualTo(10.0);
    }

    @Test
    void buildDashboard_veiculoHibrido_retornaBreakdownComDoisVetores() {
        vehicle.setEnergyType(EnergyType.HYBRID);

        when(refuelRepository.countByVehicleId(10L)).thenReturn(10L);
        when(refuelRepository.getTotalSpentByVehicleId(10L))
                .thenReturn(Optional.of(BigDecimal.valueOf(5090.60)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.empty());

        when(refuelRepository.getTotalEnergyByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.of(BigDecimal.valueOf(820.5)));
        when(refuelRepository.getTotalSpentByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.of(BigDecimal.valueOf(4860.10)));
        when(refuelRepository.getAveragePricePerUnitByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.of(BigDecimal.valueOf(5.92)));
        when(refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(List.of());

        when(refuelRepository.getTotalEnergyByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.of(BigDecimal.valueOf(189.75)));
        when(refuelRepository.getTotalSpentByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.of(BigDecimal.valueOf(230.50)));
        when(refuelRepository.getAveragePricePerUnitByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.of(BigDecimal.valueOf(1.21)));
        when(refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(List.of());

        DashboardDTO body = dashboardCacheService.buildDashboard(vehicle);

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

    /**
     * Contrato da fórmula oficial de consumo médio (ver Javadoc de buildDashboard).
     *
     * Cenário: 3 abastecimentos tanque-cheio [C(3000 km, 40 L), B(2200 km, 35 L), A(1500 km, 30 L)]
     * Par C-B: 800 km / 40 L; par B-A: 700 km / 35 L
     * Consumo esperado = (800+700) / (40+35) = 1500/75 = 20.00 km/L
     */
    @Test
    void buildDashboard_formulaOficial_tresAbastecimentosTanqueCheio() {
        Refuel refuelC = new Refuel();
        refuelC.setOdometer(3000);
        refuelC.setEnergyAmount(BigDecimal.valueOf(40.0));
        refuelC.setFullTank(true);
        refuelC.setRefuelDate(LocalDateTime.now());

        Refuel refuelB = new Refuel();
        refuelB.setOdometer(2200);
        refuelB.setEnergyAmount(BigDecimal.valueOf(35.0));
        refuelB.setFullTank(true);
        refuelB.setRefuelDate(LocalDateTime.now().minusDays(7));

        Refuel refuelA = new Refuel();
        refuelA.setOdometer(1500);
        refuelA.setEnergyAmount(BigDecimal.valueOf(30.0));
        refuelA.setFullTank(true);
        refuelA.setRefuelDate(LocalDateTime.now().minusDays(14));

        when(refuelRepository.countByVehicleId(10L)).thenReturn(3L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(900)));
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(105)));
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(8.57)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.of(refuelC));
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of(refuelC, refuelB, refuelA));

        DashboardDTO body = dashboardCacheService.buildDashboard(vehicle);

        assertThat(body.getAverageConsumption()).isEqualTo(20.0);
    }

    /**
     * Contrato da fórmula de custo por km: usa TODOS os abastecimentos (cheios ou
     * parciais), ordenados por odômetro, e não exige tanque cheio.
     *
     * Cenário: A(km 1000, R$50), B(km 1500, R$60), C(km 2300, R$80)
     * Par B-A: 500 km / R$60; par C-B: 800 km / R$80
     * Custo por km esperado = (60+80) / (500+800) = 140/1300 = 0,1077 ≈ 0,11
     */
    @Test
    void buildDashboard_comTresAbastecimentos_calculaCustoPorKm() {
        Refuel refuelC = new Refuel();
        refuelC.setOdometer(2300);
        refuelC.setTotalAmount(BigDecimal.valueOf(80));
        refuelC.setRefuelDate(LocalDateTime.now());

        Refuel refuelB = new Refuel();
        refuelB.setOdometer(1500);
        refuelB.setTotalAmount(BigDecimal.valueOf(60));
        refuelB.setRefuelDate(LocalDateTime.now().minusDays(7));

        Refuel refuelA = new Refuel();
        refuelA.setOdometer(1000);
        refuelA.setTotalAmount(BigDecimal.valueOf(50));
        refuelA.setRefuelDate(LocalDateTime.now().minusDays(14));

        when(refuelRepository.countByVehicleId(10L)).thenReturn(3L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(190)));
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(30)));
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(6.0)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.of(refuelC));
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of());
        when(refuelRepository.findByVehicleIdOrderByOdometerDesc(10L))
                .thenReturn(List.of(refuelC, refuelB, refuelA));

        DashboardDTO body = dashboardCacheService.buildDashboard(vehicle);

        assertThat(body.getCostPerKm()).isEqualByComparingTo(BigDecimal.valueOf(0.11));
    }

    @Test
    void buildDashboard_comMenosDeDoisAbastecimentos_custoPorKmEhZero() {
        Refuel singleRefuel = new Refuel();
        singleRefuel.setOdometer(1500);
        singleRefuel.setTotalAmount(BigDecimal.valueOf(80));
        singleRefuel.setRefuelDate(LocalDateTime.now());

        when(refuelRepository.countByVehicleId(10L)).thenReturn(1L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.of(singleRefuel));
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of());
        when(refuelRepository.findByVehicleIdOrderByOdometerDesc(10L)).thenReturn(List.of(singleRefuel));

        DashboardDTO body = dashboardCacheService.buildDashboard(vehicle);

        assertThat(body.getCostPerKm()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Em veículos HYBRID, custo por km combina fuel + electric naturalmente,
     * pois usa todos os refuels do veículo (independente do tipo).
     */
    @Test
    void buildDashboard_veiculoHibrido_custoPorKmCombinaFuelEElectric() {
        vehicle.setEnergyType(EnergyType.HYBRID);

        Refuel electricRefuel = new Refuel();
        electricRefuel.setOdometer(2000);
        electricRefuel.setTotalAmount(BigDecimal.valueOf(40));
        electricRefuel.setRefuelDate(LocalDateTime.now());
        electricRefuel.setRefuelType(RefuelType.ELECTRIC);

        Refuel fuelRefuel = new Refuel();
        fuelRefuel.setOdometer(1500);
        fuelRefuel.setTotalAmount(BigDecimal.valueOf(60));
        fuelRefuel.setRefuelDate(LocalDateTime.now().minusDays(7));
        fuelRefuel.setRefuelType(RefuelType.FUEL);

        when(refuelRepository.countByVehicleId(10L)).thenReturn(2L);
        when(refuelRepository.getTotalSpentByVehicleId(10L))
                .thenReturn(Optional.of(BigDecimal.valueOf(100)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findByVehicleIdOrderByOdometerDesc(10L))
                .thenReturn(List.of(electricRefuel, fuelRefuel));

        when(refuelRepository.getTotalEnergyByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.empty());
        when(refuelRepository.getTotalSpentByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.empty());
        when(refuelRepository.getAveragePricePerUnitByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.empty());
        when(refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(List.of());

        when(refuelRepository.getTotalEnergyByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.empty());
        when(refuelRepository.getTotalSpentByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.empty());
        when(refuelRepository.getAveragePricePerUnitByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.empty());
        when(refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(List.of());

        DashboardDTO body = dashboardCacheService.buildDashboard(vehicle);

        // 500 km percorridos, R$40 gastos no trecho mais recente -> 40/500 = 0,08
        assertThat(body.getCostPerKm()).isEqualByComparingTo(BigDecimal.valueOf(0.08));
    }

    @Test
    void buildDashboard_comApenasUmTanqueCheio_consumoEhZero() {
        Refuel singleRefuel = new Refuel();
        singleRefuel.setOdometer(1500);
        singleRefuel.setEnergyAmount(BigDecimal.valueOf(50.0));
        singleRefuel.setFullTank(true);
        singleRefuel.setRefuelDate(LocalDateTime.now());

        when(refuelRepository.countByVehicleId(10L)).thenReturn(1L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.of(singleRefuel));
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of(singleRefuel));

        DashboardDTO body = dashboardCacheService.buildDashboard(vehicle);

        assertThat(body).isNotNull();
        assertThat(body.getAverageConsumption()).isEqualTo(0.0);
    }
}
```

- [ ] **Step 5: Run all four affected test files**

Run: `mvn -q test -Dtest=DashboardServiceTest,DashboardCacheServiceTest,DashboardControllerIntegrationTest`
Expected: PASS — `DashboardServiceTest` (3 tests), `DashboardCacheServiceTest` (12 tests), `DashboardControllerIntegrationTest` (unchanged, still exercises the full stack through HTTP).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/dashboard/DashboardCacheService.java src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java src/test/java/com/devappmobile/flowfuel/dashboard/DashboardCacheServiceTest.java
git commit -m "refactor: extract DashboardCacheService and add @Cacheable boundary for dashboard"
```

---

### Task 5: Evict `dashboard` cache from `RefuelService`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTest.java`

`createRefuel` and `updateRefuel` both return a `RefuelResponseDTO` that already carries `vehicleId`, so `@CacheEvict` can key off `#result.vehicleId` (evicted only after a successful return, never on a thrown exception — `@CacheEvict`'s default `beforeInvocation=false`). `deleteRefuel` returns `void`, so there's no `#result` to key off — it needs to evict the cache programmatically via an injected `CacheManager`, after determining the vehicle id but before the row disappears.

- [ ] **Step 1: Update `RefuelServiceTest` for the new `CacheManager` dependency and the delete-eviction behavior**

Read the current `RefuelServiceTest.java` first. Add these two mock fields after the existing ones (`refuelRepository`, `vehicleRepository`, `authorizationHelper`):

```java
    @Mock private org.springframework.cache.CacheManager cacheManager;
    @Mock private org.springframework.cache.Cache dashboardCache;
```

Add this new test (anywhere in the class, e.g. right after the existing delete-related tests — search the file for `deleteRefuel` to find them):

```java
    @Test
    void deleteRefuel_existente_evictaDashboardDoVeiculo() {
        Refuel refuel = new Refuel();
        refuel.setId(5L);
        Vehicle vehicleOfRefuel = new Vehicle();
        vehicleOfRefuel.setId(77L);
        refuel.setVehicle(vehicleOfRefuel);

        when(refuelRepository.findById(5L)).thenReturn(java.util.Optional.of(refuel));
        when(cacheManager.getCache("dashboard")).thenReturn(dashboardCache);

        refuelService.deleteRefuel(owner, 5L);

        verify(authorizationHelper).ensureOwnsRefuel(owner, refuel);
        verify(refuelRepository).deleteById(5L);
        verify(dashboardCache).evict(77L);
    }
```

Check the existing `@BeforeEach`/fields in the file for the exact name of the `User` instance used by other passing `deleteRefuel`/`ensureOwnsRefuel` tests (the example above assumes a field called `owner` — adjust to match whatever the file actually calls its test user, e.g. it might be `user` instead; read the file to confirm before writing this test).

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=RefuelServiceTest`
Expected: FAIL — `RefuelService`'s constructor doesn't accept a `CacheManager` yet (compilation/wiring error), and `deleteRefuel` doesn't call `cacheManager.getCache(...)` yet.

- [ ] **Step 3: Add the `CacheManager` dependency and the three eviction points**

In `RefuelService.java`, add imports:

```java
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
```

Add field (after `authorizationHelper`):

```java
    private final CacheManager cacheManager;
```

Annotate `createRefuel` (the `@Transactional` annotation stays, `@CacheEvict` is added above it):

```java
    @Transactional
    @CacheEvict(cacheNames = "dashboard", key = "#result.vehicleId")
    public RefuelResponseDTO createRefuel(User user, RefuelRequestDTO request) {
```

(Leave the method body exactly as-is — only the annotation line is added.)

Annotate `updateRefuel`:

```java
    @CacheEvict(cacheNames = "dashboard", key = "#result.vehicleId")
    public RefuelResponseDTO updateRefuel(User user, Long id, RefuelRequestDTO request) {
```

(Leave the method body exactly as-is — only the annotation line is added.)

Update `deleteRefuel` to evict programmatically before/while deleting:

```java
    public void deleteRefuel(User user, Long id) {
        Refuel refuel = refuelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Abastecimento", id));
        authorizationHelper.ensureOwnsRefuel(user, refuel);
        Long vehicleId = refuel.getVehicle().getId();
        refuelRepository.deleteById(id);
        Cache dashboardCache = cacheManager.getCache("dashboard");
        if (dashboardCache != null) {
            dashboardCache.evict(vehicleId);
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=RefuelServiceTest`
Expected: PASS (all tests, including the new one)

- [ ] **Step 5: Run the broader refuel test suite to confirm no regressions**

Run: `mvn -q test -Dtest=RefuelServiceTest,RefuelServiceTransactionalTest,RefuelControllerIntegrationTest`
Expected: PASS — `RefuelControllerIntegrationTest` exercises the real `CacheManager` bean from the Spring context (via `CacheConfig`), so this also proves the DI wiring works end-to-end, not just in the mocked unit test.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTest.java
git commit -m "feat: evict dashboard cache on refuel create/update/delete"
```

---

### Task 6: Evict `dashboard` cache from `VehicleService.updateVehicle`/`deleteVehicle`, cache + evict `active-vehicle`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`

All four annotations in this task use method parameters directly available at the call site (`id`, `user.id`, `vehicleId`) — no `CacheManager` injection needed here, unlike Task 5's `deleteRefuel`.

- [ ] **Step 1: Add imports**

```java
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
```

- [ ] **Step 2: Annotate `getActiveVehicle` as cacheable**

```java
    @Cacheable(cacheNames = "active-vehicle", key = "#user.id")
    public VehicleResponseDTO getActiveVehicle(User user) {
```

(Leave the method body exactly as-is — only the annotation line is added.)

- [ ] **Step 3: Annotate `updateVehicle` to evict the dashboard cache**

```java
    @CacheEvict(cacheNames = "dashboard", key = "#id")
    public VehicleResponseDTO updateVehicle(User user, Long id, VehicleRequestDTO request) {
```

(Leave the method body exactly as-is — only the annotation line is added. `id` is the vehicle id, available directly as a method parameter, so eviction happens regardless of what changed — including `energyType`, which is what makes the dashboard's shape differ between flat fields and the HYBRID `breakdown`.)

- [ ] **Step 4: Annotate `setActiveVehicle` to evict the active-vehicle cache**

```java
    @Transactional
    @CacheEvict(cacheNames = "active-vehicle", key = "#user.id")
    public void setActiveVehicle(User user, Long vehicleId) {
```

(Leave the method body exactly as-is — only the annotation line is added, alongside the existing `@Transactional`.)

- [ ] **Step 5: Annotate `deleteVehicle` to evict BOTH caches**

```java
    @Caching(evict = {
            @CacheEvict(cacheNames = "dashboard", key = "#id"),
            @CacheEvict(cacheNames = "active-vehicle", key = "#user.id")
    })
    public void deleteVehicle(User user, Long id) {
```

(Leave the method body exactly as-is — only the annotation block is added. Both evictions always run on every successful delete, regardless of whether the deleted vehicle was actually the active one — unconditional eviction is simpler and correct: an unnecessary evict is a no-op cache miss next read, never a correctness problem.)

- [ ] **Step 6: Compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS — no constructor changes were needed (annotations only), so `VehicleServiceTest`'s existing `@InjectMocks` wiring is unaffected.

- [ ] **Step 7: Run the existing Vehicle test suite to confirm no regressions**

Run: `mvn -q test -Dtest=VehicleServiceTest,VehicleControllerIntegrationTest`
Expected: PASS — Mockito-based unit tests don't exercise Spring AOP, so they can't verify the annotations actually trigger caching/eviction; that proof comes from the Task 7 integration tests. This step only confirms nothing broke.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java
git commit -m "feat: cache active-vehicle and evict dashboard/active-vehicle caches on vehicle writes"
```

---

### Task 7: Integration tests proving cache hit, eviction, and fail-open

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardCacheIntegrationTest.java`

This is the only place in the whole feature where the `@Cacheable`/`@CacheEvict` annotations are actually exercised through Spring's real AOP proxies against a real Redis (via Testcontainers) — everything in Tasks 4-6 only compiles/wires correctly, it doesn't prove caching *behavior*. Follow the same Testcontainers pattern already used in `src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterIntegrationTest.java` (`@Container` + `@DynamicPropertySource`), but for `spring.data.redis.host`/`spring.data.redis.port` instead of the rate-limit-specific property.

- [ ] **Step 1: Write the test class**

```java
package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DashboardCacheIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private RefuelRepository refuelRepository;

    @BeforeEach
    void limparBanco() {
        refuelRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String obterToken(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123","name":"User"}
                        """.formatted(email)));
        userRepository.findByEmail(email).ifPresent(u -> {
            u.setStatus(com.devappmobile.flowfuel.user.UserStatus.ACTIVE);
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

    private long criarVeiculo(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/vehicles")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"Carro","energyType":"COMBUSTION","currentKm":1000,"capacity":55}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void criarAbastecimento(String token, long vehicleId, int odometer) throws Exception {
        mockMvc.perform(post("/api/v1/refuels")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId": %d, "odometer": %d, "energyAmount": 40, "pricePerUnit": 5.50}
                        """.formatted(vehicleId, odometer)))
                .andExpect(status().isOk());
    }

    private long getTotalRefuels(String token, long vehicleId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/dashboard/vehicle/" + vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("totalRefuels").asLong();
    }

    @Test
    void dashboard_evictadoAoCriarNovoAbastecimentoViaApi() throws Exception {
        String token = obterToken("cache1@test.com");
        long vehicleId = criarVeiculo(token);
        criarAbastecimento(token, vehicleId, 1100);

        assertThat(getTotalRefuels(token, vehicleId)).isEqualTo(1L);

        criarAbastecimento(token, vehicleId, 1200);

        assertThat(getTotalRefuels(token, vehicleId)).isEqualTo(2L);
    }

    @Test
    void dashboard_servidoDoCacheQuandoEscritaBypassaOServico() throws Exception {
        String token = obterToken("cache2@test.com");
        long vehicleId = criarVeiculo(token);
        criarAbastecimento(token, vehicleId, 1100);

        assertThat(getTotalRefuels(token, vehicleId)).isEqualTo(1L);

        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElseThrow();
        Refuel bypassRefuel = new Refuel();
        bypassRefuel.setVehicle(vehicle);
        bypassRefuel.setOdometer(1300);
        bypassRefuel.setEnergyAmount(BigDecimal.valueOf(30));
        bypassRefuel.setPricePerUnit(BigDecimal.valueOf(5.5));
        bypassRefuel.setRefuelType(RefuelType.FUEL);
        bypassRefuel.setRefuelDate(LocalDateTime.now());
        refuelRepository.save(bypassRefuel);

        // Escrita direta no repositorio nao passa por RefuelService.createRefuel,
        // logo nao executa o @CacheEvict — a leitura seguinte deve continuar
        // vindo do cache (valor antigo), provando que o cache esta de fato ativo.
        assertThat(getTotalRefuels(token, vehicleId)).isEqualTo(1L);
    }

    @Test
    void dashboard_continuaFuncionandoQuandoRedisIndisponivel() throws Exception {
        String token = obterToken("cache3@test.com");
        long vehicleId = criarVeiculo(token);
        criarAbastecimento(token, vehicleId, 1100);

        redis.stop();

        assertThat(getTotalRefuels(token, vehicleId)).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `mvn -q test -Dtest=DashboardCacheIntegrationTest`
Expected: PASS (3 tests). If `dashboard_continuaFuncionandoQuandoRedisIndisponivel` fails because the second `getTotalRefuels` call hangs or errors instead of falling back, the `CacheConfig`/`FailOpenCacheErrorHandler` wiring from Task 3 is not actually being applied — re-check that `CacheConfig implements CachingConfigurer` and the `errorHandler()` override is present (a plain unregistered `@Bean` of type `CacheErrorHandler` is silently ignored by Spring's caching infrastructure; it must come through `CachingConfigurer`).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/dashboard/DashboardCacheIntegrationTest.java
git commit -m "test: prove dashboard cache hit, eviction, and fail-open behavior end-to-end"
```

---

### Task 8: Full regression run

- [ ] **Step 1: Run the full test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests pass — no regressions in `DashboardService`, `DashboardCacheService`, `RefuelService`, `VehicleService`, or any controller integration test.

- [ ] **Step 2: Sanity-check the dev compose still works with the new dependency**

Run: `grep -n "REDIS_URL" docker-compose.yml`
Expected: the `REDIS_URL: redis://redis:6379` line (added when rate-limiting was provisioned) already covers the new Spring Data Redis connection — no `docker-compose.yml` changes are needed for this feature, since both the rate-limit Lettuce client and the new cache `RedisConnectionFactory` read the same `REDIS_URL` env var (rate-limit via the custom `flowfuel.rate-limit.redis-url` property, cache via the Spring Boot-standard `spring.data.redis.url` property added in Task 1 — both default-resolve from the same env var).
