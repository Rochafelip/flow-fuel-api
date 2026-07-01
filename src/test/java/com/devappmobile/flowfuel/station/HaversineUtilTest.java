package com.devappmobile.flowfuel.station;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HaversineUtilTest {

    @Test
    void distanceMeters_mesmoPonto_retornaZero() {
        assertThat(HaversineUtil.distanceMeters(-8.05, -34.90, -8.05, -34.90)).isZero();
    }

    @Test
    void distanceMeters_doisPontosConhecidos_calculaDistanciaAproximada() {
        // Recife (-8.0476, -34.8770) -> Olinda (-7.9936, -34.8394): ~7km
        int distance = HaversineUtil.distanceMeters(-8.0476, -34.8770, -7.9936, -34.8394);
        assertThat(distance).isBetween(6000, 8000);
    }
}
