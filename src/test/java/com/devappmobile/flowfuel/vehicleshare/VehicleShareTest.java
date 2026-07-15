package com.devappmobile.flowfuel.vehicleshare;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleShareTest {

    @Test
    void isCurrentlyActive_statusAtivoEDentroDoPrazo_retornaTrue() {
        VehicleShare share = new VehicleShare();
        share.setStatus(VehicleShareStatus.ACTIVE);
        share.setExpiresAt(LocalDateTime.now().plusHours(1));

        assertThat(share.isCurrentlyActive()).isTrue();
    }

    @Test
    void isCurrentlyActive_statusAtivoMasPrazoVencido_retornaFalse() {
        VehicleShare share = new VehicleShare();
        share.setStatus(VehicleShareStatus.ACTIVE);
        share.setExpiresAt(LocalDateTime.now().minusHours(1));

        assertThat(share.isCurrentlyActive()).isFalse();
    }

    @Test
    void isCurrentlyActive_statusPending_retornaFalse() {
        VehicleShare share = new VehicleShare();
        share.setStatus(VehicleShareStatus.PENDING);
        share.setExpiresAt(LocalDateTime.now().plusHours(1));

        assertThat(share.isCurrentlyActive()).isFalse();
    }

    @Test
    void isCurrentlyActive_semExpiresAt_retornaFalse() {
        VehicleShare share = new VehicleShare();
        share.setStatus(VehicleShareStatus.ACTIVE);

        assertThat(share.isCurrentlyActive()).isFalse();
    }
}
