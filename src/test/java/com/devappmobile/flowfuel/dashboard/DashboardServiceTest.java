package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.refuel.RefuelType;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private RefuelRepository refuelRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private AuthorizationHelper authorizationHelper;

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
        vehicle.setEnergyType(EnergyType.COMBUSTION);
    }

    @Test
    void getVehicleDashboard_veiculoInexistente_lancaResourceNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dashboardService.getVehicleDashboard(owner, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getVehicleDashboard_usuarioNaoEDono_lancaForbidden() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

        assertThatThrownBy(() -> dashboardService.getVehicleDashboard(otherUser, 10L))
                .isInstanceOf(ForbiddenOperationException.class);
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

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

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

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body).isNotNull();
        assertThat(body.getTotalRefuels()).isEqualTo(5L);
        assertThat(body.getTotalSpent()).isEqualByComparingTo(BigDecimal.valueOf(1500.00));
        assertThat(body.getTotalEnergy()).isEqualByComparingTo(BigDecimal.valueOf(250.0));
        assertThat(body.getEnergyType()).isEqualTo(EnergyType.COMBUSTION);
        assertThat(body.getEnergyUnit()).isEqualTo("litros");
        assertThat(body.getPriceUnit()).isEqualTo("R$/litro");
        assertThat(body.getConsumptionUnit()).isEqualTo("km/L");
    }

    @Test
    void getVehicleDashboard_veiculoEletrico_retornaUnidadesEmKwh() {
        vehicle.setEnergyType(EnergyType.ELECTRIC);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.countByVehicleId(10L)).thenReturn(3L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(180.00)));
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(150.0)));
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(1.20)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of());

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body.getEnergyType()).isEqualTo(EnergyType.ELECTRIC);
        assertThat(body.getEnergyUnit()).isEqualTo("kWh");
        assertThat(body.getPriceUnit()).isEqualTo("R$/kWh");
        assertThat(body.getConsumptionUnit()).isEqualTo("km/kWh");
        assertThat(body.getTotalEnergy()).isEqualByComparingTo(BigDecimal.valueOf(150.0));
    }

    @Test
    void getVehicleDashboard_comDoisTanquesCheios_calculaConsumoMedio() {
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

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body).isNotNull();
        assertThat(body.getAverageConsumption()).isEqualTo(10.0);
    }

    @Test
    void getVehicleDashboard_veiculoHibrido_retornaBreakdownComDoisVetores() {
        vehicle.setEnergyType(EnergyType.HYBRID);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.countByVehicleId(10L)).thenReturn(10L);
        when(refuelRepository.getTotalSpentByVehicleId(10L))
                .thenReturn(Optional.of(BigDecimal.valueOf(5090.60)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.empty());

        when(refuelRepository.getTotalEnergyByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.of(BigDecimal.valueOf(820.5)));
        when(refuelRepository.getTotalSpentByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.of(BigDecimal.valueOf(4860.10)));
        when(refuelRepository.getAveragePricePerUnitByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.of(BigDecimal.valueOf(5.92)));
        when(refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(List.of());

        when(refuelRepository.getTotalEnergyByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.of(BigDecimal.valueOf(189.75)));
        when(refuelRepository.getTotalSpentByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.of(BigDecimal.valueOf(230.50)));
        when(refuelRepository.getAveragePricePerUnitByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.of(BigDecimal.valueOf(1.21)));
        when(refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(List.of());

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body.getEnergyType()).isEqualTo(EnergyType.HYBRID);
        assertThat(body.getTotalSpent()).isEqualByComparingTo(BigDecimal.valueOf(5090.60));
        assertThat(body.getTotalRefuels()).isEqualTo(10L);
        assertThat(body.getTotalEnergy()).isNull();
        assertThat(body.getAveragePrice()).isNull();
        assertThat(body.getEnergyUnit()).isNull();

        assertThat(body.getBreakdown()).isNotNull();
        assertThat(body.getBreakdown().getFuel().getTotalEnergy())
                .isEqualByComparingTo(BigDecimal.valueOf(820.5));
        assertThat(body.getBreakdown().getFuel().getEnergyUnit()).isEqualTo("litros");
        assertThat(body.getBreakdown().getFuel().getPriceUnit()).isEqualTo("R$/litro");
        assertThat(body.getBreakdown().getElectric().getTotalEnergy())
                .isEqualByComparingTo(BigDecimal.valueOf(189.75));
        assertThat(body.getBreakdown().getElectric().getEnergyUnit()).isEqualTo("kWh");
        assertThat(body.getBreakdown().getElectric().getConsumptionUnit()).isEqualTo("km/kWh");
    }

    /**
     * Contrato da fórmula oficial de consumo médio (ver Javadoc de calculateAverageConsumption).
     * Usado como referência para futuras otimizações (M5).
     *
     * Cenário: 3 abastecimentos tanque-cheio [C(3000 km, 40 L), B(2200 km, 35 L), A(1500 km, 30 L)]
     * Par C-B: 800 km / 40 L; par B-A: 700 km / 35 L
     * Consumo esperado = (800+700) / (40+35) = 1500/75 = 20.00 km/L
     */
    @Test
    void calculateAverageConsumption_formulaOficial_tresAbastecimentosTanqueCheio() {
        Refuel refuelC = new Refuel();
        refuelC.setOdometer(3000);
        refuelC.setEnergyAmount(BigDecimal.valueOf(40.0));
        refuelC.setFullTank(true);
        refuelC.setRefuelDate(LocalDateTime.now());

        Refuel refuelB = new Refuel();
        refuelB.setOdometer(2200);
        refuelB.setEnergyAmount(BigDecimal.valueOf(35.0));
        refuelB.setFullTank(true);
        refuelB.setRefuelDate(LocalDateTime.now().minusDays(7));

        Refuel refuelA = new Refuel();
        refuelA.setOdometer(1500);
        refuelA.setEnergyAmount(BigDecimal.valueOf(30.0));
        refuelA.setFullTank(true);
        refuelA.setRefuelDate(LocalDateTime.now().minusDays(14));

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.countByVehicleId(10L)).thenReturn(3L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(900)));
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(105)));
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(8.57)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.of(refuelC));
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of(refuelC, refuelB, refuelA));

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body.getAverageConsumption()).isEqualTo(20.0);
    }

    /**
     * Contrato da fórmula de custo por km: usa TODOS os abastecimentos (cheios ou
     * parciais), ordenados por odômetro, e não exige tanque cheio (diferente de
     * averageConsumption).
     *
     * Cenário: A(km 1000, R$50), B(km 1500, R$60), C(km 2300, R$80)
     * Par B-A: 500 km / R$60; par C-B: 800 km / R$80
     * Custo por km esperado = (60+80) / (500+800) = 140/1300 = 0,1077 ≈ 0,11
     */
    @Test
    void getVehicleDashboard_comTresAbastecimentos_calculaCustoPorKm() {
        Refuel refuelC = new Refuel();
        refuelC.setOdometer(2300);
        refuelC.setTotalAmount(BigDecimal.valueOf(80));
        refuelC.setRefuelDate(LocalDateTime.now());

        Refuel refuelB = new Refuel();
        refuelB.setOdometer(1500);
        refuelB.setTotalAmount(BigDecimal.valueOf(60));
        refuelB.setRefuelDate(LocalDateTime.now().minusDays(7));

        Refuel refuelA = new Refuel();
        refuelA.setOdometer(1000);
        refuelA.setTotalAmount(BigDecimal.valueOf(50));
        refuelA.setRefuelDate(LocalDateTime.now().minusDays(14));

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.countByVehicleId(10L)).thenReturn(3L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(190)));
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(30)));
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.of(BigDecimal.valueOf(6.0)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.of(refuelC));
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of());
        when(refuelRepository.findByVehicleIdOrderByOdometerDesc(10L))
                .thenReturn(List.of(refuelC, refuelB, refuelA));

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body.getCostPerKm()).isEqualByComparingTo(BigDecimal.valueOf(0.11));
    }

    @Test
    void getVehicleDashboard_comMenosDeDoisAbastecimentos_custoPorKmEhZero() {
        Refuel singleRefuel = new Refuel();
        singleRefuel.setOdometer(1500);
        singleRefuel.setTotalAmount(BigDecimal.valueOf(80));
        singleRefuel.setRefuelDate(LocalDateTime.now());

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.countByVehicleId(10L)).thenReturn(1L);
        when(refuelRepository.getTotalSpentByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getTotalEnergyByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.getAveragePricePerUnitByVehicleId(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.of(singleRefuel));
        when(refuelRepository.findFullTankRefuelsByVehicleId(10L)).thenReturn(List.of());
        when(refuelRepository.findByVehicleIdOrderByOdometerDesc(10L)).thenReturn(List.of(singleRefuel));

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body.getCostPerKm()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Em veículos HYBRID, custo por km combina fuel + electric naturalmente,
     * pois usa todos os refuels do veículo (independente do tipo).
     */
    @Test
    void getVehicleDashboard_veiculoHibrido_custoPorKmCombinaFuelEElectric() {
        vehicle.setEnergyType(EnergyType.HYBRID);

        Refuel electricRefuel = new Refuel();
        electricRefuel.setOdometer(2000);
        electricRefuel.setTotalAmount(BigDecimal.valueOf(40));
        electricRefuel.setRefuelDate(LocalDateTime.now());
        electricRefuel.setRefuelType(RefuelType.ELECTRIC);

        Refuel fuelRefuel = new Refuel();
        fuelRefuel.setOdometer(1500);
        fuelRefuel.setTotalAmount(BigDecimal.valueOf(60));
        fuelRefuel.setRefuelDate(LocalDateTime.now().minusDays(7));
        fuelRefuel.setRefuelType(RefuelType.FUEL);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(refuelRepository.countByVehicleId(10L)).thenReturn(2L);
        when(refuelRepository.getTotalSpentByVehicleId(10L))
                .thenReturn(Optional.of(BigDecimal.valueOf(100)));
        when(refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(Optional.empty());
        when(refuelRepository.findByVehicleIdOrderByOdometerDesc(10L))
                .thenReturn(List.of(electricRefuel, fuelRefuel));

        when(refuelRepository.getTotalEnergyByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.empty());
        when(refuelRepository.getTotalSpentByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.empty());
        when(refuelRepository.getAveragePricePerUnitByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(Optional.empty());
        when(refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(10L, RefuelType.FUEL))
                .thenReturn(List.of());

        when(refuelRepository.getTotalEnergyByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.empty());
        when(refuelRepository.getTotalSpentByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.empty());
        when(refuelRepository.getAveragePricePerUnitByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(Optional.empty());
        when(refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(10L, RefuelType.ELECTRIC))
                .thenReturn(List.of());

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        // 500 km percorridos, R$40 gastos no trecho mais recente -> 40/500 = 0,08
        assertThat(body.getCostPerKm()).isEqualByComparingTo(BigDecimal.valueOf(0.08));
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

        DashboardDTO body = dashboardService.getVehicleDashboard(owner, 10L);

        assertThat(body).isNotNull();
        assertThat(body.getAverageConsumption()).isEqualTo(0.0);
    }
}
