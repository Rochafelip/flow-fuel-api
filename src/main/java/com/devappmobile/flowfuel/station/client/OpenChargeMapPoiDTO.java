package com.devappmobile.flowfuel.station.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OpenChargeMapPoiDTO {

    @JsonProperty("ID")
    private Long id;

    @JsonProperty("AddressInfo")
    private AddressInfo addressInfo;

    @Getter
    @Setter
    public static class AddressInfo {

        @JsonProperty("Title")
        private String title;

        @JsonProperty("AddressLine1")
        private String addressLine1;

        @JsonProperty("Latitude")
        private Double latitude;

        @JsonProperty("Longitude")
        private Double longitude;
    }
}
