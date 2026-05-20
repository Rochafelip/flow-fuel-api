package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.dto.VehicleRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    @Mock private VehicleRepository vehicleRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private VehicleService vehicleService;

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
        vehicle.setType("Carro");
        vehicle.setIsActive(true);
    }

    // --- createVehicle ---

    @Test
    void createVehicle_dadosValidos_retornaVeiculoSalvo() {
        VehicleRequestDTO dto = new VehicleRequestDTO();
        dto.setType("Carro");
        dto.setEnergyType(EnergyType.COMBUSTION);
        dto.setCurrentKm(5000);
        dto.setCapacity(50);

        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(inv -> {
            Vehicle v = inv.getArgument(0);
            v.setId(99L);
            return v;
        });

        ResponseEntity<?> response = vehicleService.createVehicle(owner, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- getUserVehicles ---

    @Test
    void getUserVehicles_retornaListaDoUsuario() {
        when(vehicleRepository.findByUserId(1L)).thenReturn(List.of(vehicle));

        ResponseEntity<List<Vehicle>> response = vehicleService.getUserVehicles(owner);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    // --- getActiveVehicle ---

    @Test
    void getActiveVehicle_quandoExiste_retornaVeiculo() {
        when(vehicleRepository.findByUserId(1L)).thenReturn(List.of(vehicle));

        ResponseEntity<Vehicle> response = vehicleService.getActiveVehicle(owner);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getId()).isEqualTo(10L);
    }

    @Test
    void getActiveVehicle_quandoNenhumAtivo_retorna404() {
        vehicle.setIsActive(false);
        when(vehicleRepository.findByUserId(1L)).thenReturn(List.of(vehicle));

        ResponseEntity<Vehicle> response = vehicleService.getActiveVehicle(owner);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- getVehicleById ---

    @Test
    void getVehicleById_donoCorreto_retornaVeiculo() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        ResponseEntity<Vehicle> response = vehicleService.getVehicleById(owner, 10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getId()).isEqualTo(10L);
    }

    @Test
    void getVehicleById_usuarioNaoEDono_retorna403() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        ResponseEntity<Vehicle> response = vehicleService.getVehicleById(otherUser, 10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getVehicleById_veiculoInexistente_retorna404() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<Vehicle> response = vehicleService.getVehicleById(owner, 99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- updateOdometer ---

    @Test
    void updateOdometer_valorMaior_atualizaComSucesso() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleRepository.save(any())).thenReturn(vehicle);

        ResponseEntity<Vehicle> response = vehicleService.updateOdometer(owner, 10L, 2000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(vehicle.getCurrentKm()).isEqualTo(2000);
    }

    @Test
    void updateOdometer_valorMenorQueAtual_retorna400() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        ResponseEntity<Vehicle> response = vehicleService.updateOdometer(owner, 10L, 500);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(vehicleRepository, never()).save(any());
    }

    // --- setActiveVehicle ---

    @Test
    void setActiveVehicle_desativaOutrosEAtivaSelecionado() {
        Vehicle v2 = new Vehicle();
        v2.setId(20L);
        v2.setUser(owner);
        v2.setIsActive(false);

        when(vehicleRepository.findById(20L)).thenReturn(Optional.of(v2));
        when(vehicleRepository.findByUserId(1L)).thenReturn(List.of(vehicle, v2));
        when(vehicleRepository.saveAll(any())).thenReturn(List.of(vehicle, v2));

        ResponseEntity<?> response = vehicleService.setActiveVehicle(owner, 20L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(vehicle.getIsActive()).isFalse();
        assertThat(v2.getIsActive()).isTrue();
    }

    // --- deleteVehicle ---

    @Test
    void deleteVehicle_donoCorreto_deletaERetorna200() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        ResponseEntity<?> response = vehicleService.deleteVehicle(owner, 10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(vehicleRepository).deleteById(10L);
    }

    @Test
    void deleteVehicle_usuarioNaoEDono_retorna403SemDeletar() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        ResponseEntity<?> response = vehicleService.deleteVehicle(otherUser, 10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(vehicleRepository, never()).deleteById(any());
    }
}
