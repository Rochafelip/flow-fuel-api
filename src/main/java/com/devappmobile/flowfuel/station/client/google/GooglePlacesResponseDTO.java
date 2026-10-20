package com.devappmobile.flowfuel.station.client.google;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GooglePlacesResponseDTO {
    private List<Place> places;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Place {
        private String id;
        private DisplayName displayName;
        private String formattedAddress;
        private Location location;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DisplayName {
        @JsonProperty("text")
        private String text;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Location {
        private Double latitude;
        private Double longitude;
    }
}
