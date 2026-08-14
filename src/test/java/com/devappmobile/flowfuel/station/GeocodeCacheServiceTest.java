package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeocodeCacheServiceTest {

    @Mock private ObjectProvider<StatefulRedisConnection<String, byte[]>> connectionProvider;
    @Mock private StatefulRedisConnection<String, byte[]> connection;
    @Mock private RedisCommands<String, byte[]> commands;

    private GeocodeCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new GeocodeCacheService(connectionProvider, new ObjectMapper());
    }

    @Test
    void get_semConexaoDisponivel_retornaVazioFailOpen() {
        when(connectionProvider.getIfAvailable()).thenReturn(null);

        assertThat(cacheService.get("key")).isEmpty();
    }

    @Test
    void get_cacheMiss_retornaVazio() {
        when(connectionProvider.getIfAvailable()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        when(commands.get("key")).thenReturn(null);

        assertThat(cacheService.get("key")).isEmpty();
    }

    @Test
    void get_cacheHit_deserializaLista() {
        GeocodeResultDTO place = GeocodeResultDTO.builder()
                .displayName("Boa Viagem, Recife, Pernambuco, Brasil")
                .latitude(-8.12).longitude(-34.90).build();
        ObjectMapper mapper = new ObjectMapper();
        byte[] serialized;
        try {
            serialized = mapper.writeValueAsBytes(List.of(place));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(connectionProvider.getIfAvailable()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        when(commands.get("key")).thenReturn(serialized);

        Optional<List<GeocodeResultDTO>> result = cacheService.get("key");

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).getDisplayName()).isEqualTo("Boa Viagem, Recife, Pernambuco, Brasil");
    }

    @Test
    void get_redisLancaExcecao_failOpenRetornaVazio() {
        when(connectionProvider.getIfAvailable()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        when(commands.get("key")).thenThrow(new RedisException("down"));

        assertThat(cacheService.get("key")).isEmpty();
    }

    @Test
    void put_semConexaoDisponivel_naoLancaExcecao() {
        when(connectionProvider.getIfAvailable()).thenReturn(null);

        cacheService.put("key", List.of());
        // sem excecao = sucesso (fail-open)
    }

    @Test
    void put_comConexao_chamaSetComTtl() {
        when(connectionProvider.getIfAvailable()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);

        cacheService.put("key", List.of());

        verify(commands).set(eq("key"), any(byte[].class), any());
    }
}
