package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class VehicleServiceTransactionalTest {

    @Autowired
    private VehicleService vehicleService;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private VehicleRepository vehicleRepository;

    private User user;
    private Vehicle activeVehicle;
    private Vehicle inactiveVehicle;

    @BeforeEach
    void setUp() {
        vehicleRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(new User("vehicle-tx@test.com", "hash", "User"));

        activeVehicle = vehicleRepository.save(newVehicle(user, true));
        inactiveVehicle = vehicleRepository.save(newVehicle(user, false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void setActiveVehicle_falhaNoMeioDoSaveAll_naoAlteraNenhumVeiculo() {
        Long activeId = activeVehicle.getId();
        Long inactiveId = inactiveVehicle.getId();

        doAnswer(invocation -> {
            List<Vehicle> vehicles = (List<Vehicle>) invocation.getArgument(0);
            // Simula sucesso parcial do lote: persiste isoladamente a mudanca
            // do veiculo que estava ativo (agora marcado como inativo) antes
            // de falhar — sem @Transactional no service, isso fica commitado.
            vehicles.stream()
                    .filter(v -> v.getId().equals(activeId))
                    .findFirst()
                    .ifPresent(vehicleRepository::save);
            throw new RuntimeException("falha simulada no meio do saveAll");
        }).when(vehicleRepository).saveAll(anyList());

        assertThatThrownBy(() -> vehicleService.setActiveVehicle(user, inactiveId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("falha simulada no meio do saveAll");

        assertThat(vehicleRepository.findById(activeId).orElseThrow().getIsActive()).isTrue();
        assertThat(vehicleRepository.findById(inactiveId).orElseThrow().getIsActive()).isFalse();
    }

    private Vehicle newVehicle(User owner, boolean active) {
        Vehicle vehicle = new Vehicle();
        vehicle.setType("Carro");
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCurrentKm(10000);
        vehicle.setCapacity(50);
        vehicle.setIsActive(active);
        vehicle.setUser(owner);
        return vehicle;
    }
}
