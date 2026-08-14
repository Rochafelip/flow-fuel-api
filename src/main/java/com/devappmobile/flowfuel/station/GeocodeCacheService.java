package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Cache Redis raw para resultados de geocodificacao. TTL bem mais longo
 * que StationCacheService (10min) porque coordenadas de um lugar nao
 * mudam. Fail-open: qualquer falha vira cache miss.
 */
@Service
public class GeocodeCacheService {

    private static final Logger log = LoggerFactory.getLogger(GeocodeCacheService.class);
    private static final Duration TTL = Duration.ofDays(7);
    private static final TypeReference<List<GeocodeResultDTO>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectProvider<StatefulRedisConnection<String, byte[]>> connectionProvider;
    private final ObjectMapper objectMapper;

    public GeocodeCacheService(ObjectProvider<StatefulRedisConnection<String, byte[]>> connectionProvider,
            ObjectMapper objectMapper) {
        this.connectionProvider = connectionProvider;
        this.objectMapper = objectMapper;
    }

    public Optional<List<GeocodeResultDTO>> get(String key) {
        StatefulRedisConnection<String, byte[]> connection = connectionProvider.getIfAvailable();
        if (connection == null) {
            return Optional.empty();
        }
        try {
            byte[] value = connection.sync().get(key);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, LIST_TYPE));
        } catch (Exception e) {
            log.warn("Geocode cache indisponivel (get), fail-open. key={} error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, List<GeocodeResultDTO> results) {
        StatefulRedisConnection<String, byte[]> connection = connectionProvider.getIfAvailable();
        if (connection == null) {
            return;
        }
        try {
            byte[] value = objectMapper.writeValueAsBytes(results);
            connection.sync().set(key, value, new SetArgs().ex(TTL.toSeconds()));
        } catch (Exception e) {
            log.warn("Geocode cache indisponivel (put), fail-open. key={} error={}", key, e.getMessage());
        }
    }
}
