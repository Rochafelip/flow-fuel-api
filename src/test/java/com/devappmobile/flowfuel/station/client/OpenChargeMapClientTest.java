package com.devappmobile.flowfuel.station.client;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.StationType;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenChargeMapClientTest {

    private static final String BASE_URL = "https://api.openchargemap.io/v3/poi";

    private MockRestServiceServer server;

    private OpenChargeMapClient buildClient(String apiKey) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new OpenChargeMapClient(builder, BASE_URL, apiKey);
    }

    @Test
    void findChargingStations_respostaValida_mapeiaResultados() {
        OpenChargeMapClient client = buildClient("");
        String body = """
                [
                  {"ID":42,"AddressInfo":{"Title":"Posto Eletrico","Latitude":-8.05,"Longitude":-34.90}}
                ]
                """;
        server.expect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<StationResponseDTO> result = client.findChargingStations(-8.05, -34.90, 5000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlaceId()).isEqualTo("ocm:42");
        assertThat(result.get(0).getName()).isEqualTo("Posto Eletrico");
        assertThat(result.get(0).getType()).isEqualTo(StationType.ELECTRIC);
        assertThat(result.get(0).getRating()).isNull();
    }

    @Test
    void findChargingStations_poiSemAddressInfoOuCoordenadas_filtraResultado() {
        OpenChargeMapClient client = buildClient("");
        String body = """
                [
                  {"ID":99},
                  {"ID":98,"AddressInfo":{"Title":"Sem coordenadas"}},
                  {"ID":42,"AddressInfo":{"Title":"Posto Eletrico","Latitude":-8.05,"Longitude":-34.90}}
                ]
                """;
        server.expect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<StationResponseDTO> result = client.findChargingStations(-8.05, -34.90, 5000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlaceId()).isEqualTo("ocm:42");
    }

    @Test
    void findChargingStations_comApiKey_enviaHeaderXApiKey() {
        OpenChargeMapClient client = buildClient("minha-key");
        server.expect(method(GET))
                .andExpect(header("X-API-Key", "minha-key"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.findChargingStations(-8.05, -34.90, 5000)).isEmpty();
    }

    @Test
    void findChargingStations_ocmRetorna500_lancaExternalServiceUnavailable() {
        OpenChargeMapClient client = buildClient("");
        server.expect(method(GET)).andRespond(withServerError());

        assertThatThrownBy(() -> client.findChargingStations(-8.05, -34.90, 5000))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }
}
