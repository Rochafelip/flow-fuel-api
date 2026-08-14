package com.devappmobile.flowfuel.station.client;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
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

class NominatimClientTest {

    private static final String BASE_URL = "https://nominatim.openstreetmap.org/search";

    private MockRestServiceServer server;

    private NominatimClient buildClient() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new NominatimClient(builder, BASE_URL);
    }

    @Test
    void search_respostaValida_mapeiaResultadosEConverteLatLonParaDouble() {
        NominatimClient client = buildClient();
        String body = """
                [
                  {"lat":"-8.1200000","lon":"-34.9000000","display_name":"Boa Viagem, Recife, Pernambuco, Brasil"}
                ]
                """;
        server.expect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<GeocodeResultDTO> result = client.search("Boa Viagem, Recife");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDisplayName()).isEqualTo("Boa Viagem, Recife, Pernambuco, Brasil");
        assertThat(result.get(0).getLatitude()).isEqualTo(-8.12);
        assertThat(result.get(0).getLongitude()).isEqualTo(-34.90);
    }

    @Test
    void search_semResultados_retornaListaVazia() {
        NominatimClient client = buildClient();
        server.expect(method(GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.search("lugarquenaoexiste")).isEmpty();
    }

    @Test
    void search_enviaHeaderUserAgentIdentificavel() {
        NominatimClient client = buildClient();
        server.expect(method(GET))
                .andExpect(header("User-Agent", "FlowFuel/1.0 (+https://flowfuel-api.fly.dev)"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.search("Boa Viagem");
    }

    @Test
    void search_nominatimRetorna500_lancaExternalServiceUnavailable() {
        NominatimClient client = buildClient();
        server.expect(method(GET)).andRespond(withServerError());

        assertThatThrownBy(() -> client.search("Boa Viagem"))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    @Test
    void search_latLonInvalidos_lancaExternalServiceUnavailable() {
        NominatimClient client = buildClient();
        String body = """
                [
                  {"lat":"abc","lon":"-34.90","display_name":"Lugar Estranho"}
                ]
                """;
        server.expect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.search("Boa Viagem"))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }
}
