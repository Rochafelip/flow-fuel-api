package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.vehicle.Vehicle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void addVehicle_onFreshlyInstantiatedUser_addsVehicleAndSetsBackReference() {
        User user = new User();
        Vehicle vehicle = new Vehicle();

        user.addVehicle(vehicle);

        assertThat(user.getVehicles()).containsExactly(vehicle);
        assertThat(vehicle.getUser()).isSameAs(user);
    }

    @Test
    void removeVehicle_onFreshlyInstantiatedUser_removesVehicleAndClearsBackReference() {
        User user = new User();
        Vehicle vehicle = new Vehicle();
        user.addVehicle(vehicle);

        user.removeVehicle(vehicle);

        assertThat(user.getVehicles()).isEmpty();
        assertThat(vehicle.getUser()).isNull();
    }
}
