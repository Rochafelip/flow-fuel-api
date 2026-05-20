package com.devappmobile.flowfuel.refuel;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefuelServiceTest {

    @Mock private RefuelRepository refuelRepository;
    @Mock private VehicleRepository vehicleRepository;

    @InjectMocks private RefuelService refuelService;

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
        vehicle.setCurrentKm(1000);
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCapacity(55);
    }

    private RefuelRequestDTO buildRequest(int odometer, double energyAmount, double price) {
        RefuelRequestDTO dto = new RefuelRequestDTO();
        dto.setVehicleId(10L);
        dto.setOdometer(odometer);
        dto.setEnergyAmount(BigDecimal.valueOf(energyAmount));
        dto.setPricePerUnit(BigDecimal.valueOf(price));
        dto.setFullTank(true);
        return dto;
    }

    // --- createRefuel ---

    @Test
    void createRefuel_dadosValidos_retornaRefuelComKmCalculado() {
        RefuelRequestDTO dto = buildRequest(1500, 40.0, 5.89);

        Refuel saved = new Refuel();
        saved.setId(1L);
        saved.setOdometer(1500);
        saved.setKmSinceLastRefuel(500);
        saved.setEnergyAmount(BigDecimal.valueOf(40.0));
        saved.setPricePerUnit(BigDecimal.valueOf(5.89));
        saved.setVehicle(vehicle);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.save(any(Refuel.class))).thenReturn(saved);
        when(vehicleRepository.save(any(Vehicle.class))).thenReturn(vehicle);

        ResponseEntity<Refuel> response = refuelService.createRefuel(owner, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Verifica que o odometro do veiculo foi atualizado
        verify(vehicleRepository).save(argThat(v -> v.getCurrentKm() == 1500));
    }

    @Test
    void createRefuel_odometroMenorQueUltimo_retorna400() {
        Refuel lastRefuel = new Refuel();
        lastRefuel.setOdometer(2000);

        RefuelRequestDTO dto = buildRequest(1500, 40.0, 5.89); // odometer < lastOdometer

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.of(lastRefuel));

        ResponseEntity<Refuel> response = refuelService.createRefuel(owner, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_precoMuitoBaixo_retorna400() {
        RefuelRequestDTO dto = buildRequest(1500, 40.0, 0.10); // < 0.50 para combustão

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());

        ResponseEntity<Refuel> response = refuelService.createRefuel(owner, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_precoMuitoAlto_retorna400() {
        RefuelRequestDTO dto = buildRequest(1500, 40.0, 20.0); // > 15.0 para combustão

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());

        ResponseEntity<Refuel> response = refuelService.createRefuel(owner, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_energiaMaiorQueCapacidade_retorna400() {
        RefuelRequestDTO dto = buildRequest(1500, 60.0, 5.89); // 60 > 55 (capacidade)

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());

        ResponseEntity<Refuel> response = refuelService.createRefuel(owner, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_usuarioNaoEDono_retorna403() {
        RefuelRequestDTO dto = buildRequest(1500, 40.0, 5.89);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        ResponseEntity<Refuel> response = refuelService.createRefuel(otherUser, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_veiculoInexistente_retorna404() {
        RefuelRequestDTO dto = buildRequest(1500, 40.0, 5.89);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.empty());

        ResponseEntity<Refuel> response = refuelService.createRefuel(owner, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createRefuel_veiculoEletrico_aceitaPrecoNaFaixaEletrica() {
        vehicle.setEnergyType(EnergyType.ELECTRIC);
        RefuelRequestDTO dto = buildRequest(1500, 30.0, 1.50); // válido para elétrico

        Refuel saved = new Refuel();
        saved.setId(5L);
        saved.setVehicle(vehicle);
        saved.setOdometer(1500);
        saved.setEnergyAmount(BigDecimal.valueOf(30.0));
        saved.setPricePerUnit(BigDecimal.valueOf(1.50));

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.save(any())).thenReturn(saved);
        when(vehicleRepository.save(any())).thenReturn(vehicle);

        ResponseEntity<Refuel> response = refuelService.createRefuel(owner, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- getRefuelById ---

    @Test
    void getRefuelById_donoCorreto_retornaRefuel() {
        Refuel refuel = new Refuel();
        refuel.setId(1L);
        refuel.setVehicle(vehicle);

        when(refuelRepository.findById(1L)).thenReturn(Optional.of(refuel));

        ResponseEntity<Refuel> response = refuelService.getRefuelById(owner, 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getRefuelById_usuarioNaoEDono_retorna403() {
        Refuel refuel = new Refuel();
        refuel.setId(1L);
        refuel.setVehicle(vehicle);

        when(refuelRepository.findById(1L)).thenReturn(Optional.of(refuel));

        ResponseEntity<Refuel> response = refuelService.getRefuelById(otherUser, 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- deleteRefuel ---

    @Test
    void deleteRefuel_donoCorreto_deletaERetorna200() {
        Refuel refuel = new Refuel();
        refuel.setId(1L);
        refuel.setVehicle(vehicle);

        when(refuelRepository.findById(1L)).thenReturn(Optional.of(refuel));

        ResponseEntity<?> response = refuelService.deleteRefuel(owner, 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(refuelRepository).deleteById(1L);
    }

    @Test
    void deleteRefuel_usuarioNaoEDono_retorna403SemDeletar() {
        Refuel refuel = new Refuel();
        refuel.setId(1L);
        refuel.setVehicle(vehicle);

        when(refuelRepository.findById(1L)).thenReturn(Optional.of(refuel));

        ResponseEntity<?> response = refuelService.deleteRefuel(otherUser, 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(refuelRepository, never()).deleteById(any());
    }

    // --- getVehicleRefuels ---

    @Test
    void getVehicleRefuels_semFiltroDeData_retornaListaCompleta() {
        Refuel r1 = new Refuel();
        r1.setId(1L);
        r1.setVehicle(vehicle);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(List.of(r1));

        ResponseEntity<List<Refuel>> response = refuelService.getVehicleRefuels(owner, 10L, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }
}
