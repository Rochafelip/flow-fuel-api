package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.common.PageResponseDTO;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefuelServiceTest {

    @Mock private RefuelRepository refuelRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private AuthorizationHelper authorizationHelper;

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

        RefuelResponseDTO response = refuelService.createRefuel(owner, dto);

        assertThat(response).isNotNull();
        assertThat(response.getKmSinceLastRefuel()).isEqualTo(500);
        verify(vehicleRepository).save(argThat(v -> v.getCurrentKm() == 1500));
    }

    @Test
    void createRefuel_odometroMenorQueUltimo_lancaBusinessRule() {
        Refuel lastRefuel = new Refuel();
        lastRefuel.setOdometer(2000);

        RefuelRequestDTO dto = buildRequest(1500, 40.0, 5.89);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.of(lastRefuel));

        assertThatThrownBy(() -> refuelService.createRefuel(owner, dto))
                .isInstanceOf(BusinessRuleException.class);
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_precoMuitoBaixo_lancaBusinessRule() {
        RefuelRequestDTO dto = buildRequest(1500, 40.0, 0.10);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refuelService.createRefuel(owner, dto))
                .isInstanceOf(BusinessRuleException.class);
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_precoMuitoAlto_lancaBusinessRule() {
        RefuelRequestDTO dto = buildRequest(1500, 40.0, 20.0);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refuelService.createRefuel(owner, dto))
                .isInstanceOf(BusinessRuleException.class);
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_energiaMaiorQueCapacidade_lancaBusinessRule() {
        RefuelRequestDTO dto = buildRequest(1500, 60.0, 5.89);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refuelService.createRefuel(owner, dto))
                .isInstanceOf(BusinessRuleException.class);
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_usuarioNaoEDono_lancaForbidden() {
        RefuelRequestDTO dto = buildRequest(1500, 40.0, 5.89);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

        assertThatThrownBy(() -> refuelService.createRefuel(otherUser, dto))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_veiculoInexistente_lancaResourceNotFound() {
        RefuelRequestDTO dto = buildRequest(1500, 40.0, 5.89);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refuelService.createRefuel(owner, dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createRefuel_veiculoHibrido_semRefuelType_lancaBusinessRule() {
        vehicle.setEnergyType(EnergyType.HYBRID);
        vehicle.setBatteryCapacity(BigDecimal.valueOf(40));

        RefuelRequestDTO dto = buildRequest(1500, 30.0, 5.50);
        dto.setRefuelType(null);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refuelService.createRefuel(owner, dto))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("refuelType");
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_veiculoHibrido_comRefuelTypeEletrico_validaContraBatteryCapacity() {
        vehicle.setEnergyType(EnergyType.HYBRID);
        vehicle.setBatteryCapacity(BigDecimal.valueOf(40));

        RefuelRequestDTO dto = buildRequest(1500, 50.0, 1.20);
        dto.setRefuelType(RefuelType.ELECTRIC);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refuelService.createRefuel(owner, dto))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("capacidade");
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_veiculoCombustao_comRefuelTypeEletrico_lancaBusinessRule() {
        RefuelRequestDTO dto = buildRequest(1500, 30.0, 1.20);
        dto.setRefuelType(RefuelType.ELECTRIC);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refuelService.createRefuel(owner, dto))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("incompatível");
        verify(refuelRepository, never()).save(any());
    }

    @Test
    void createRefuel_veiculoEletricoSemBatteryCapacity_naoValidaCapacidade() {
        vehicle.setEnergyType(EnergyType.ELECTRIC);
        vehicle.setCapacity(null);
        vehicle.setBatteryCapacity(null);

        RefuelRequestDTO dto = buildRequest(1500, 999.0, 1.20);

        Refuel saved = new Refuel();
        saved.setId(7L);
        saved.setVehicle(vehicle);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findTopByVehicleIdOrderByOdometerDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.save(any())).thenReturn(saved);
        when(vehicleRepository.save(any())).thenReturn(vehicle);

        RefuelResponseDTO response = refuelService.createRefuel(owner, dto);

        assertThat(response).isNotNull();
    }

    @Test
    void createRefuel_veiculoEletrico_aceitaPrecoNaFaixaEletrica() {
        vehicle.setEnergyType(EnergyType.ELECTRIC);
        RefuelRequestDTO dto = buildRequest(1500, 30.0, 1.50);

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

        RefuelResponseDTO response = refuelService.createRefuel(owner, dto);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(5L);
    }

    // --- getRefuelById ---

    @Test
    void getRefuelById_donoCorreto_retornaRefuel() {
        Refuel refuel = new Refuel();
        refuel.setId(1L);
        refuel.setVehicle(vehicle);

        when(refuelRepository.findById(1L)).thenReturn(Optional.of(refuel));

        RefuelResponseDTO response = refuelService.getRefuelById(owner, 1L);

        assertThat(response.getId()).isEqualTo(1L);
    }

    @Test
    void getRefuelById_usuarioNaoEDono_lancaForbidden() {
        Refuel refuel = new Refuel();
        refuel.setId(1L);
        refuel.setVehicle(vehicle);

        when(refuelRepository.findById(1L)).thenReturn(Optional.of(refuel));
        doThrow(new ForbiddenOperationException("Abastecimento não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsRefuel(otherUser, refuel);

        assertThatThrownBy(() -> refuelService.getRefuelById(otherUser, 1L))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    // --- deleteRefuel ---

    @Test
    void deleteRefuel_donoCorreto_deleta() {
        Refuel refuel = new Refuel();
        refuel.setId(1L);
        refuel.setVehicle(vehicle);

        when(refuelRepository.findById(1L)).thenReturn(Optional.of(refuel));

        refuelService.deleteRefuel(owner, 1L);

        verify(refuelRepository).deleteById(1L);
    }

    @Test
    void deleteRefuel_usuarioNaoEDono_lancaForbiddenSemDeletar() {
        Refuel refuel = new Refuel();
        refuel.setId(1L);
        refuel.setVehicle(vehicle);

        when(refuelRepository.findById(1L)).thenReturn(Optional.of(refuel));
        doThrow(new ForbiddenOperationException("Abastecimento não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsRefuel(otherUser, refuel);

        assertThatThrownBy(() -> refuelService.deleteRefuel(otherUser, 1L))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(refuelRepository, never()).deleteById(any());
    }

    // --- getVehicleRefuels ---

    @Test
    void getVehicleRefuels_semFiltroDeData_retornaPaginaCompleta() {
        Refuel r1 = new Refuel();
        r1.setId(1L);
        r1.setVehicle(vehicle);

        Pageable pageable = PageRequest.of(0, 20);
        Page<Refuel> page = new PageImpl<>(List.of(r1), pageable, 1);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.findByVehicleIdOrderByRefuelDateDesc(10L, pageable)).thenReturn(page);

        PageResponseDTO<RefuelResponseDTO> response =
                refuelService.getVehicleRefuels(owner, 10L, null, null, pageable);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getTotalElements()).isEqualTo(1);
    }
}
