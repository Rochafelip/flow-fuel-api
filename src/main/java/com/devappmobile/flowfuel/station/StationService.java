package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.exception.RateLimitExceededException;
import com.devappmobile.flowfuel.station.client.NominatimClient;
import com.devappmobile.flowfuel.station.client.OpenChargeMapClient;
import com.devappmobile.flowfuel.station.client.OverpassClient;
import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Orquestra a busca de postos proximos: cache -> Overpass + Open Charge Map ->
 * merge + distancia (Haversine) + ordenacao -> cache put. Rate limit por
 * userId antes de qualquer chamada. Fail-open tanto para cache quanto para
 * rate limit quando o Redis/ProxyManager nao estao disponiveis.
 */
@Service
public class StationService {

    private static final Logger log = LoggerFactory.getLogger(StationService.class);

    private static final BucketConfiguration STATION_RATE_LIMIT = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder().capacity(10).refillGreedy(10, Duration.ofMinutes(1)).build())
            .build();

    private static final BucketConfiguration NOMINATIM_GLOBAL_RATE_LIMIT = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder().capacity(1).refillGreedy(1, Duration.ofSeconds(1)).build())
            .build();

    private final OverpassClient overpassClient;
    private final OpenChargeMapClient openChargeMapClient;
    private final StationCacheService cacheService;
    private final ObjectProvider<ProxyManager<String>> proxyManagerProvider;
    private final NominatimClient nominatimClient;
    private final GeocodeCacheService geocodeCacheService;

    public StationService(OverpassClient overpassClient, OpenChargeMapClient openChargeMapClient,
            StationCacheService cacheService, ObjectProvider<ProxyManager<String>> proxyManagerProvider,
            NominatimClient nominatimClient, GeocodeCacheService geocodeCacheService) {
        this.overpassClient = overpassClient;
        this.openChargeMapClient = openChargeMapClient;
        this.cacheService = cacheService;
        this.proxyManagerProvider = proxyManagerProvider;
        this.nominatimClient = nominatimClient;
        this.geocodeCacheService = geocodeCacheService;
    }

    public List<StationResponseDTO> findNearby(Long userId, double lat, double lng, int radiusMeters) {
        checkRateLimit(userId);

        String cacheKey = buildCacheKey(lat, lng, radiusMeters);
        Optional<List<StationResponseDTO>> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        List<StationResponseDTO> fuelStations = null;
        List<StationResponseDTO> electricStations = null;
        Exception lastError = null;

        try {
            fuelStations = overpassClient.findFuelStations(lat, lng, radiusMeters);
        } catch (Exception e) {
            log.warn("Overpass falhou, seguindo so com Open Charge Map: {}", e.getMessage());
            lastError = e;
        }

        try {
            electricStations = openChargeMapClient.findChargingStations(lat, lng, radiusMeters);
        } catch (Exception e) {
            log.warn("Open Charge Map falhou, seguindo so com Overpass: {}", e.getMessage());
            lastError = e;
        }

        if (fuelStations == null && electricStations == null) {
            throw new ExternalServiceUnavailableException(
                    "Overpass e Open Charge Map indisponiveis", lastError);
        }

        List<StationResponseDTO> merged = new ArrayList<>();
        if (fuelStations != null) merged.addAll(fuelStations);
        if (electricStations != null) merged.addAll(electricStations);

        merged.forEach(station -> station.setDistanceMeters(
                HaversineUtil.distanceMeters(lat, lng, station.getLatitude(), station.getLongitude())));
        merged.sort(Comparator.comparing(StationResponseDTO::getDistanceMeters));

        cacheService.put(cacheKey, merged);
        return merged;
    }

    public List<GeocodeResultDTO> geocode(Long userId, String query) {
        checkRateLimit(userId);
        checkGeocodeGlobalRateLimit();

        String cacheKey = "stations:geocode:" + query.trim().toLowerCase(Locale.forLanguageTag("pt-BR"));
        Optional<List<GeocodeResultDTO>> cached = geocodeCacheService.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        List<GeocodeResultDTO> results = nominatimClient.search(query.trim());
        geocodeCacheService.put(cacheKey, results);
        return results;
    }

    private void checkGeocodeGlobalRateLimit() {
        ProxyManager<String> proxyManager = proxyManagerProvider.getIfAvailable();
        if (proxyManager == null) {
            return;
        }
        ConsumptionProbe probe;
        try {
            BucketProxy bucket = proxyManager.builder().build("nominatim-global-rate-limit", () -> NOMINATIM_GLOBAL_RATE_LIMIT);
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (Exception e) {
            log.warn("Rate limit global do Nominatim indisponivel, fail-open. error={}", e.getMessage());
            return;
        }
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(1L,
                    Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L));
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }

    private void checkRateLimit(Long userId) {
        ProxyManager<String> proxyManager = proxyManagerProvider.getIfAvailable();
        if (proxyManager == null) {
            return;
        }
        String bucketKey = "station-rate-limit::" + userId;
        ConsumptionProbe probe;
        try {
            BucketProxy bucket = proxyManager.builder().build(bucketKey, () -> STATION_RATE_LIMIT);
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (Exception e) {
            log.warn("Rate limit Redis indisponivel para stations, fail-open. userId={} error={}",
                    userId, e.getMessage());
            return;
        }
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(1L,
                    Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L));
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }

    private String buildCacheKey(double lat, double lng, int radiusMeters) {
        return "stations:nearby:%s:%s:%d".formatted(round(lat), round(lng), radiusMeters);
    }

    private String round(double value) {
        return String.format(Locale.US, "%.3f", value);
    }
}
