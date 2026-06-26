package com.devappmobile.flowfuel.export.util;

import com.devappmobile.flowfuel.export.ExportFormat;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import org.junit.jupiter.api.Test;

import java.time.Year;

import static org.assertj.core.api.Assertions.assertThat;

class ExportFileNameBuilderTest {

    @Test
    void build_comMarcaEModelo_geraNomeEmKebabCaseSemAcentos() {
        Vehicle vehicle = new Vehicle();
        vehicle.setBrand("Toyotá");
        vehicle.setModel("Corolla Híbrido");

        String fileName = ExportFileNameBuilder.build("refuels", vehicle, ExportFormat.CSV);

        assertThat(fileName).isEqualTo(
                "flowfuel-refuels-toyota-corolla-hibrido-" + Year.now().getValue() + ".csv");
    }

    @Test
    void build_comFormatoXlsx_usaExtensaoXlsx() {
        Vehicle vehicle = new Vehicle();
        vehicle.setBrand("Honda");
        vehicle.setModel("Civic");

        String fileName = ExportFileNameBuilder.build("events", vehicle, ExportFormat.XLSX);

        assertThat(fileName).isEqualTo(
                "flowfuel-events-honda-civic-" + Year.now().getValue() + ".xlsx");
    }

    @Test
    void build_semMarcaNemModelo_usaVeiculoMaisId() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(42L);

        String fileName = ExportFileNameBuilder.build("refuels", vehicle, ExportFormat.CSV);

        assertThat(fileName).isEqualTo(
                "flowfuel-refuels-veiculo-42-" + Year.now().getValue() + ".csv");
    }
}
