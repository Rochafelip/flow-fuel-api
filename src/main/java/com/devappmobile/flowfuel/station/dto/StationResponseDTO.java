package com.devappmobile.flowfuel.station.dto;

import com.devappmobile.flowfuel.station.StationType;
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
public class StationResponseDTO {

    private String placeId;
    private String name;
    private StationType type;
    private Integer distanceMeters;
    private Double rating;
    private Double latitude;
    private Double longitude;
}
