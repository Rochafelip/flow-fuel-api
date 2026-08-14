package com.devappmobile.flowfuel.vehicleevent;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class VehicleEventRepositoryAggregateTest {

    @Autowired private VehicleEventRepository vehicleEventRepository;
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

    private void saveEvent(BigDecimal amount) {
        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);
        event.setType(VehicleEventType.TAX);
        event.setAmount(amount);
        event.setEventDate(LocalDate.of(2026, 1, 10));
        vehicleEventRepository.save(event);
    }

    @Test
    void getTotalAmountByVehicleId_semEventos_retornaVazio() {
        assertThat(vehicleEventRepository.getTotalAmountByVehicleId(vehicle.getId())).isEmpty();
    }

    @Test
    void getTotalAmountByVehicleId_comEventos_somaTodosOsValores() {
        saveEvent(BigDecimal.valueOf(210.70));
        saveEvent(BigDecimal.valueOf(92.60));
        saveEvent(BigDecimal.valueOf(140.00));

        BigDecimal total = vehicleEventRepository.getTotalAmountByVehicleId(vehicle.getId())
                .orElseThrow();
        assertThat(total).isEqualByComparingTo(BigDecimal.valueOf(443.30));
    }
}
