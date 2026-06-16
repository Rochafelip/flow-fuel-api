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
