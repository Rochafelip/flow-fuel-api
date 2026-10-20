package com.devappmobile.flowfuel.station.client.google;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Circle {
    private Center center;
    private Double radius;
}
