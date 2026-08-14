package com.devappmobile.flowfuel.station.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeocodeResultDTO {
    private String displayName;
    private Double latitude;
    private Double longitude;
}
