package com.devappmobile.flowfuel.common;

import com.devappmobile.flowfuel.devicetoken.DeviceToken;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationHelper {

    public void ensureOwnsVehicle(User user, Vehicle vehicle) {
        if (!vehicle.getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Veículo não pertence ao usuário");
        }
    }

    public void ensureOwnsRefuel(User user, Refuel refuel) {
        if (!refuel.getVehicle().getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Abastecimento não pertence ao usuário");
        }
    }

    public void ensureOwnsEvent(User user, VehicleEvent event) {
        if (!event.getVehicle().getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Evento não pertence ao usuário");
        }
    }

    public void ensureOwnsDeviceToken(User user, DeviceToken deviceToken) {
        if (!deviceToken.getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Token de dispositivo não pertence ao usuário");
        }
    }

    public void ensureIsAdmin(User user) {
        if (!user.isAdmin()) {
            throw new ForbiddenOperationException("Operação restrita a administradores");
        }
    }
}
