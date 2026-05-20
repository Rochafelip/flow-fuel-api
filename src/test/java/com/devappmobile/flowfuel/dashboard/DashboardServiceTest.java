package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.user.User;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private RefuelRepository refuelRepository;
    @Mock private VehicleRepository vehicleRepository;

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
    }

    // --- getVehicleDashboard ---

    @Test
    void getVehicleDashboard_veiculoInexistente_retorna404() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<DashboardDTO> response = dashboardService.getVehicleDashboard(owner, 99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getVehicleDashboard_usuarioNaoEDono_retorna403() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        ResponseEntity<DashboardDTO> response = dashboardService.getVehicleDashboard(otherUser, 10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getVehicleDashboard_semAbastecimentos_retornaMetricasZeradas() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.countByVehicleId(10L)).thenReturn(0L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of());

        ResponseEntity<DashboardDTO> response = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DashboardDTO body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTotalRefuels()).isEqualTo(0L);
        assertThat(body.getTotalSpent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.getAverageConsumption()).isEqualTo(0.0);
    }

    @Test
    void getVehicleDashboard_comAbastecimentos_retornaTotaisCorretos() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.countByVehicleId(10L)).thenReturn(5L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(1500.00)));
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(250.0)));
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(6.00)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of());

        ResponseEntity<DashboardDTO> response = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DashboardDTO body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTotalRefuels()).isEqualTo(5L);
        assertThat(body.getTotalSpent()).isEqualByComparingTo(BigDecimal.valueOf(1500.00));
        assertThat(body.getTotalEnergy()).isEqualByComparingTo(BigDecimal.valueOf(250.0));
    }

    @Test
    void getVehicleDashboard_comDoisTanquesCheios_calculaConsumoMedio() {
        // Refuels ordenados DESC por data: mais recente primeiro
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

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.countByVehicleId(10L)).thenReturn(2L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(500)));
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(95)));
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(5.26)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.of(recent));
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of(recent, older));

        ResponseEntity<DashboardDTO> response = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DashboardDTO body = response.getBody();
        assertThat(body).isNotNull();
        // consumo = (2000 - 1500) / 50.0 = 10.0 km/L
        assertThat(body.getAverageConsumption()).isEqualTo(10.0);
    }

    @Test
    void getVehicleDashboard_comApenasUmTanqueCheio_consumoEhZero() {
        Refuel singleRefuel = new Refuel();
        singleRefuel.setOdometer(1500);
        singleRefuel.setEnergyAmount(BigDecimal.valueOf(50.0));
        singleRefuel.setFullTank(true);
        singleRefuel.setRefuelDate(LocalDateTime.now());

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.countByVehicleId(10L)).thenReturn(1L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.of(singleRefuel));
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of(singleRefuel));

        ResponseEntity<DashboardDTO> response = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getAverageConsumption()).isEqualTo(0.0);
    }
}
