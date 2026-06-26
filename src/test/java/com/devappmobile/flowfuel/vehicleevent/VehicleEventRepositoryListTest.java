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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class VehicleEventRepositoryListTest {

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

        saveEvent(VehicleEventType.MAINTENANCE, LocalDate.of(2026, 1, 10));
        saveEvent(VehicleEventType.CAR_WASH, LocalDate.of(2026, 3, 5));
        saveEvent(VehicleEventType.MAINTENANCE, LocalDate.of(2026, 6, 1));
    }

    private void saveEvent(VehicleEventType type, LocalDate date) {
        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);
        event.setType(type);
        event.setAmount(BigDecimal.valueOf(100));
        event.setEventDate(date);
        vehicleEventRepository.save(event);
    }

    @Test
    void findByVehicleId_semFiltro_retornaTodosOrdenadosPorDataDesc() {
        List<VehicleEvent> result = vehicleEventRepository
                .findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(vehicle.getId());

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getEventDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(result.get(2).getEventDate()).isEqualTo(LocalDate.of(2026, 1, 10));
    }

    @Test
    void findByVehicleIdAndType_filtraPorTipo() {
        List<VehicleEvent> result = vehicleEventRepository
                .findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(
                        vehicle.getId(), VehicleEventType.MAINTENANCE);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> e.getType() == VehicleEventType.MAINTENANCE);
    }

    @Test
    void findByVehicleIdAndEventDateBetween_filtraPorPeriodo() {
        List<VehicleEvent> result = vehicleEventRepository
                .findByVehicleIdAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                        vehicle.getId(), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo(VehicleEventType.CAR_WASH);
    }

    @Test
    void findByVehicleIdAndTypeAndEventDateBetween_filtraPorTipoEPeriodo() {
        List<VehicleEvent> result = vehicleEventRepository
                .findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                        vehicle.getId(), VehicleEventType.MAINTENANCE,
                        LocalDate.of(2026, 5, 1), LocalDate.of(2026, 12, 31));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventDate()).isEqualTo(LocalDate.of(2026, 6, 1));
    }
}
