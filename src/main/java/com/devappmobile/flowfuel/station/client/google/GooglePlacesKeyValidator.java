package com.devappmobile.flowfuel.station.client.google;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Validador que garante que a API key do Google Places está configurada em produção.
 * Ver docs/superpowers/specs/2026-10-20-google-places-integration-design.md.
 */
@Component
@Profile("prod")
public class GooglePlacesKeyValidator implements ApplicationListener<ApplicationReadyEvent> {

    @Value("${flowfuel.station.google-places.api-key:}")
    private String apiKey;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "GOOGLE_PLACES_API_KEY é obrigatória em produção. Configure via env var.");
        }
    }
}
