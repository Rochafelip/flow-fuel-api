package com.devappmobile.flowfuel.common;

import com.devappmobile.flowfuel.devicetoken.DeviceToken;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationHelperTest {

    private AuthorizationHelper helper;
    private User owner;
    private User other;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        helper = new AuthorizationHelper();

        owner = new User("owner@test.com", "hash", "Owner");
        owner.setId(1L);

        other = new User("other@test.com", "hash", "Other");
        other.setId(2L);

        vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setUser(owner);
    }

    // --- ensureOwnsVehicle ---

    @Test
    void ensureOwnsVehicle_owner_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> helper.ensureOwnsVehicle(owner, vehicle));
    }

    @Test
    void ensureOwnsVehicle_notOwner_throwsForbidden() {
        assertThatThrownBy(() -> helper.ensureOwnsVehicle(other, vehicle))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Veículo não pertence ao usuário");
    }

    // --- ensureOwnsRefuel ---

    @Test
    void ensureOwnsRefuel_owner_doesNotThrow() {
        Refuel refuel = new Refuel();
        refuel.setVehicle(vehicle);

        assertThatNoException().isThrownBy(() -> helper.ensureOwnsRefuel(owner, refuel));
    }

    @Test
    void ensureOwnsRefuel_notOwner_throwsForbidden() {
        Refuel refuel = new Refuel();
        refuel.setVehicle(vehicle);

        assertThatThrownBy(() -> helper.ensureOwnsRefuel(other, refuel))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Abastecimento não pertence ao usuário");
    }

    // --- ensureOwnsEvent ---

    @Test
    void ensureOwnsEvent_owner_doesNotThrow() {
        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);

        assertThatNoException().isThrownBy(() -> helper.ensureOwnsEvent(owner, event));
    }

    @Test
    void ensureOwnsEvent_notOwner_throwsForbidden() {
        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);

        assertThatThrownBy(() -> helper.ensureOwnsEvent(other, event))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Evento não pertence ao usuário");
    }

    // --- ensureOwnsDeviceToken ---

    @Test
    void ensureOwnsDeviceToken_owner_doesNotThrow() {
        DeviceToken deviceToken = new DeviceToken();
        deviceToken.setUser(owner);

        assertThatNoException().isThrownBy(() -> helper.ensureOwnsDeviceToken(owner, deviceToken));
    }

    @Test
    void ensureOwnsDeviceToken_notOwner_throwsForbidden() {
        DeviceToken deviceToken = new DeviceToken();
        deviceToken.setUser(owner);

        assertThatThrownBy(() -> helper.ensureOwnsDeviceToken(other, deviceToken))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Token de dispositivo não pertence ao usuário");
    }

    // --- ensureIsAdmin ---

    @Test
    void ensureIsAdmin_usuarioAdmin_doesNotThrow() {
        owner.setAdmin(true);

        assertThatNoException().isThrownBy(() -> helper.ensureIsAdmin(owner));
    }

    @Test
    void ensureIsAdmin_usuarioNaoAdmin_throwsForbidden() {
        assertThatThrownBy(() -> helper.ensureIsAdmin(other))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Operação restrita a administradores");
    }
}
