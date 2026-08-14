package com.devappmobile.flowfuel.vehicleevent;

import java.math.BigDecimal;

public record VehicleEventTypeAmountProjection(
        VehicleEventType type,
        BigDecimal totalAmount
) {}
