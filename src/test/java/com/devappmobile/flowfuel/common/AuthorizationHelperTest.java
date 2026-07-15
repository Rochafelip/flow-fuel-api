package com.devappmobile.flowfuel.common;

import com.devappmobile.flowfuel.devicetoken.DeviceToken;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import com.devappmobile.flowfuel.vehicleshare.VehicleShareRepository;
import com.devappmobile.flowfuel.vehicleshare.VehicleShareStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationHelperTest {

    @Mock
    private VehicleShareRepository vehicleShareRepository;

    private AuthorizationHelper helper;
    private User owner;
    private User other;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        helper = new AuthorizationHelper(vehicleShareRepository);

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

    // --- ensureOwnsOrHasGuestAccess ---

    @Test
    void ensureOwnsOrHasGuestAccess_owner_doesNotThrowAndSkipsRepository() {
        assertThatNoException().isThrownBy(() -> helper.ensureOwnsOrHasGuestAccess(owner, vehicle));
    }

    @Test
    void ensureOwnsOrHasGuestAccess_guestWithActiveShare_doesNotThrow() {
        when(vehicleShareRepository.existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter(
                eq(10L), eq(2L), eq(VehicleShareStatus.ACTIVE), any()))
                .thenReturn(true);

        assertThatNoException().isThrownBy(() -> helper.ensureOwnsOrHasGuestAccess(other, vehicle));
    }

    @Test
    void ensureOwnsOrHasGuestAccess_guestWithoutActiveShare_throwsForbidden() {
        when(vehicleShareRepository.existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter(
                eq(10L), eq(2L), eq(VehicleShareStatus.ACTIVE), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> helper.ensureOwnsOrHasGuestAccess(other, vehicle))
                .isInstanceOf(ForbiddenOperationException.class);
    }
}
