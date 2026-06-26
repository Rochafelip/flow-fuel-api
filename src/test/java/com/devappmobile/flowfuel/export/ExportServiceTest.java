package com.devappmobile.flowfuel.export;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.export.strategy.CsvExportStrategy;
import com.devappmobile.flowfuel.export.strategy.ExcelExportStrategy;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventRepository;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Spy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock private RefuelRepository refuelRepository;
    @Mock private VehicleEventRepository vehicleEventRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private AuthorizationHelper authorizationHelper;

    @Spy private CsvExportStrategy csvExportStrategy = new CsvExportStrategy();
    @Spy private ExcelExportStrategy excelExportStrategy = new ExcelExportStrategy();

    private ExportService exportService;

    private User owner;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        exportService = new ExportService(
                refuelRepository, vehicleEventRepository, vehicleRepository,
                authorizationHelper, List.of(csvExportStrategy, excelExportStrategy));

        owner = new User("owner@test.com", "hash", "Owner");
        owner.setId(1L);

        vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setUser(owner);
        vehicle.setBrand("Toyota");
        vehicle.setModel("Corolla");

        org.mockito.Mockito.lenient().when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
    }

    private Refuel buildRefuel(LocalDateTime date, int odometer, double energy, double price) {
        Refuel refuel = new Refuel();
        refuel.setRefuelDate(date);
        refuel.setOdometer(odometer);
        refuel.setEnergyAmount(BigDecimal.valueOf(energy));
        refuel.setPricePerUnit(BigDecimal.valueOf(price));
        refuel.setRefuelType(RefuelType.FUEL);
        refuel.calculateTotalAmount();
        refuel.setVehicle(vehicle);
        return refuel;
    }

    @Test
    void exportRefuels_semFiltroDeData_geraCsvComTodosOsAbastecimentos() {
        when(refuelRepository.findByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(List.of(
                buildRefuel(LocalDateTime.of(2026, 6, 1, 10, 0), 1500, 40.0, 5.89)
        ));

        ExportResult result = exportService.exportRefuels(owner, 10L, null, null, "csv");

        assertThat(result.fileName()).isEqualTo("flowfuel-refuels-toyota-corolla-" + java.time.Year.now().getValue() + ".csv");
        assertThat(result.contentType()).isEqualTo("text/csv");
        String content = new String(result.content());
        assertThat(content).contains("Data,Combustível,Litros/kWh,Preço/unidade,Total,Odômetro");
        assertThat(content).contains("01/06/2026");
    }

    @Test
    void exportRefuels_comPeriodo_usaQueryDeIntervalo() {
        when(refuelRepository.findByVehicleIdAndRefuelDateBetweenOrderByRefuelDateDesc(
                10L, LocalDate.of(2026, 1, 1).atStartOfDay(), LocalDate.of(2026, 12, 31).atTime(23, 59, 59)))
                .thenReturn(List.of(buildRefuel(LocalDateTime.of(2026, 3, 1, 8, 0), 1000, 35.0, 5.5)));

        ExportResult result = exportService.exportRefuels(
                owner, 10L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "xlsx");

        assertThat(result.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(result.fileName()).endsWith(".xlsx");
    }

    @Test
    void exportRefuels_vehicleInexistente_lancaResourceNotFoundException() {
        when(vehicleRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> exportService.exportRefuels(owner, 999L, null, null, "csv"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void exportRefuels_vehicleDeOutroUsuario_propagaForbiddenOperationException() {
        org.mockito.Mockito.doThrow(new ForbiddenOperationException("não autorizado"))
                .when(authorizationHelper).ensureOwnsVehicle(owner, vehicle);

        assertThatThrownBy(() -> exportService.exportRefuels(owner, 10L, null, null, "csv"))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void exportRefuels_formatoInvalido_lancaExportValidationException() {
        assertThatThrownBy(() -> exportService.exportRefuels(owner, 10L, null, null, "pdf"))
                .isInstanceOf(ExportValidationException.class);
    }

    @Test
    void exportRefuels_apenasStartDateInformada_lancaExportValidationException() {
        assertThatThrownBy(() -> exportService.exportRefuels(
                owner, 10L, LocalDate.of(2026, 1, 1), null, "csv"))
                .isInstanceOf(ExportValidationException.class);
    }

    @Test
    void exportRefuels_startDateDepoisDeEndDate_lancaExportValidationException() {
        assertThatThrownBy(() -> exportService.exportRefuels(
                owner, 10L, LocalDate.of(2026, 12, 31), LocalDate.of(2026, 1, 1), "csv"))
                .isInstanceOf(ExportValidationException.class);
    }

    private VehicleEvent buildEvent(VehicleEventType type, LocalDate date, double amount, String description) {
        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);
        event.setType(type);
        event.setEventDate(date);
        event.setAmount(BigDecimal.valueOf(amount));
        event.setDescription(description);
        event.setOdometer(2000);
        return event;
    }

    @Test
    void exportEvents_semFiltro_geraCsvComTodosOsEventos() {
        when(vehicleEventRepository.findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(10L))
                .thenReturn(List.of(buildEvent(VehicleEventType.MAINTENANCE,
                        LocalDate.of(2026, 6, 1), 150.0, "Troca de óleo")));

        ExportResult result = exportService.exportEvents(owner, 10L, null, null, null, "csv");

        String content = new String(result.content());
        assertThat(content).contains("Data,Tipo,Descrição,Valor,Odômetro");
        assertThat(content).contains("01/06/2026,MAINTENANCE,Troca de óleo,150.0,2000");
    }

    @Test
    void exportEvents_comTipo_usaQueryFiltradaPorTipo() {
        when(vehicleEventRepository.findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(
                10L, VehicleEventType.CAR_WASH))
                .thenReturn(List.of(buildEvent(VehicleEventType.CAR_WASH,
                        LocalDate.of(2026, 5, 1), 40.0, "Lavagem completa")));

        ExportResult result = exportService.exportEvents(
                owner, 10L, VehicleEventType.CAR_WASH, null, null, "csv");

        assertThat(new String(result.content())).contains("CAR_WASH");
    }

    @Test
    void exportEvents_comTipoEPeriodo_usaQueryFiltradaPorTipoEPeriodo() {
        when(vehicleEventRepository.findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                10L, VehicleEventType.MAINTENANCE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(buildEvent(VehicleEventType.MAINTENANCE,
                        LocalDate.of(2026, 6, 1), 150.0, "Revisão")));

        ExportResult result = exportService.exportEvents(owner, 10L, VehicleEventType.MAINTENANCE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "xlsx");

        assertThat(result.fileName()).endsWith(".xlsx");
    }

    @Test
    void exportEvents_descricaoComCaractereDeFormula_recebePrefixoDeEscape() {
        when(vehicleEventRepository.findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(10L))
                .thenReturn(List.of(buildEvent(VehicleEventType.MAINTENANCE,
                        LocalDate.of(2026, 6, 1), 150.0, "=cmd|'/c calc'!A1")));

        ExportResult result = exportService.exportEvents(owner, 10L, null, null, null, "csv");

        String content = new String(result.content());
        assertThat(content).contains("'=cmd|'/c calc'!A1");
    }

    @Test
    void exportEvents_apenasEndDateInformada_lancaExportValidationException() {
        assertThatThrownBy(() -> exportService.exportEvents(
                owner, 10L, null, null, LocalDate.of(2026, 1, 1), "csv"))
                .isInstanceOf(ExportValidationException.class);
    }
}
