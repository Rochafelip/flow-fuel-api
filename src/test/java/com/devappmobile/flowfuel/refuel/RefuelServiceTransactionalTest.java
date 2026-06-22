package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class RefuelServiceTransactionalTest {

    @Autowired
    private RefuelService refuelService;

    @Autowired
    private RefuelRepository refuelRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private VehicleRepository vehicleRepository;

    private User user;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        refuelRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(new User("refuel-tx@test.com", "hash", "User"));

        Vehicle newVehicle = new Vehicle();
        newVehicle.setType("Carro");
        newVehicle.setEnergyType(EnergyType.COMBUSTION);
        newVehicle.setCurrentKm(50000);
        newVehicle.setCapacity(55);
        newVehicle.setUser(user);
        vehicle = vehicleRepository.save(newVehicle);
    }

    @Test
    void createRefuel_falhaAoSalvarVeiculo_naoPersisteRefuelNemAtualizaOdometro() {
        doThrow(new RuntimeException("falha simulada ao salvar veiculo"))
                .when(vehicleRepository).save(any(Vehicle.class));

        RefuelRequestDTO request = new RefuelRequestDTO();
        request.setVehicleId(vehicle.getId());
        request.setOdometer(50500);
        request.setEnergyAmount(BigDecimal.valueOf(40));
        request.setPricePerUnit(BigDecimal.valueOf(5.89));
        request.setFullTank(true);

        assertThatThrownBy(() -> refuelService.createRefuel(user, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("falha simulada ao salvar veiculo");

        assertThat(refuelRepository.findAll()).isEmpty();
        Vehicle reloaded = vehicleRepository.findById(vehicle.getId()).orElseThrow();
        assertThat(reloaded.getCurrentKm()).isEqualTo(50000);
    }
}
