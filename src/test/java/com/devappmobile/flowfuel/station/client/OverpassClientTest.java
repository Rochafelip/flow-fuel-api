package com.devappmobile.flowfuel.station.client;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.StationType;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class OverpassClientTest {

    private static final String BASE_URL = "https://overpass-api.de/api/interpreter";

    private MockRestServiceServer server;
    private OverpassClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OverpassClient(builder, BASE_URL);
    }

    @Test
    void findFuelStations_respostaComNodeEWay_mapeiaAmbos() {
        String body = """
                {
                  "elements": [
                    {"type":"node","id":123456789,"lat":-8.05,"lon":-34.90,
                     "tags":{"name":"Posto Node"}},
                    {"type":"way","id":987654321,
                     "center":{"lat":-8.06,"lon":-34.91},
                     "tags":{"name":"Posto Way"}},
                    {"type":"relation","id":111,
                     "center":{"lat":-8.07,"lon":-34.92}}
                  ]
                }
                """;
        server.expect(requestTo(BASE_URL))
                .andExpect(method(POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<StationResponseDTO> result = client.findFuelStations(-8.05, -34.90, 5000);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getPlaceId()).isEqualTo("osm:node/123456789");
        assertThat(result.get(0).getName()).isEqualTo("Posto Node");
        assertThat(result.get(0).getType()).isEqualTo(StationType.FUEL);
        assertThat(result.get(0).getLatitude()).isEqualTo(-8.05);
        assertThat(result.get(0).getRating()).isNull();

        assertThat(result.get(1).getPlaceId()).isEqualTo("osm:way/987654321");
        assertThat(result.get(1).getLatitude()).isEqualTo(-8.06);
        assertThat(result.get(1).getLongitude()).isEqualTo(-34.91);

        assertThat(result.get(2).getPlaceId()).isEqualTo("osm:relation/111");
        assertThat(result.get(2).getName()).isEqualTo("Posto de combustível");
    }

    @Test
    void findFuelStations_semElementos_retornaListaVazia() {
        server.expect(requestTo(BASE_URL))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"elements\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.findFuelStations(-8.05, -34.90, 5000)).isEmpty();
    }

    @Test
    void findFuelStations_overpassRetorna500_lancaExternalServiceUnavailable() {
        server.expect(requestTo(BASE_URL))
                .andExpect(method(POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.findFuelStations(-8.05, -34.90, 5000))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }
}
