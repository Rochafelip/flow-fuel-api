package com.devappmobile.flowfuel.vehicleevent;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Producao (V5__vehicle_events.sql, Flyway) declara
 * "vehicle_id ... REFERENCES vehicles(id) ON DELETE CASCADE". Os testes rodam
 * com ddl-auto=create-drop (schema gerado por Hibernate a partir das
 * anotacoes da entidade), entao esse comportamento so existe no schema de
 * teste se o mapeamento JPA replicar o mesmo cascade.
 */
@DataJpaTest
class VehicleEventCascadeDeleteTest {

    @Autowired private UserRepository userRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private VehicleEventRepository vehicleEventRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void deleteVehicle_semEventoAssociado_controle() {
        User user = new User();
        user.setEmail("cascade-control@test.com");
        user.setPassword("hash");
        user.setName("Cascade Control");
        user = userRepository.save(user);

        Vehicle vehicle = new Vehicle();
        vehicle.setType("car");
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCurrentKm(0);
        vehicle.setCapacity(50);
        vehicle.setUser(user);
        vehicle = vehicleRepository.save(vehicle);

        vehicleRepository.deleteById(vehicle.getId());
        vehicleRepository.flush();

        assertThat(vehicleRepository.findById(vehicle.getId())).isEmpty();
    }

    @Test
    void deleteVehicle_comEventoAssociado_naoViolaForeignKey() {
        User user = new User();
        user.setEmail("cascade-test@test.com");
        user.setPassword("hash");
        user.setName("Cascade Test");
        user = userRepository.save(user);

        Vehicle vehicle = new Vehicle();
        vehicle.setType("car");
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCurrentKm(0);
        vehicle.setCapacity(50);
        vehicle.setUser(user);
        vehicle = vehicleRepository.save(vehicle);

        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);
        event.setType(VehicleEventType.MAINTENANCE);
        event.setAmount(BigDecimal.valueOf(100));
        event.setEventDate(LocalDate.of(2026, 1, 10));
        vehicleEventRepository.save(event);

        vehicleRepository.deleteById(vehicle.getId());
        vehicleRepository.flush();
        // ON DELETE CASCADE roda no banco, nao no Hibernate: sem clear() aqui,
        // o cache de 1o nivel ainda devolveria o "event" gerenciado em memoria.
        entityManager.clear();

        assertThat(vehicleRepository.findById(vehicle.getId())).isEmpty();
        assertThat(vehicleEventRepository.findById(event.getId())).isEmpty();
    }
}
