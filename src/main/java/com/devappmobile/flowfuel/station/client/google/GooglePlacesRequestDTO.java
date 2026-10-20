package com.devappmobile.flowfuel.station.client.google;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GooglePlacesRequestDTO {
    private Location location;
    private List<String> includedTypes;
    private Integer maxResultCount;
    private String rankPreference;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Location {
        private Double latitude;
        private Double longitude;
    }
}
