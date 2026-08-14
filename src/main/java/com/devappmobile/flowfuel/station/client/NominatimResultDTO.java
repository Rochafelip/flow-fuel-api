package com.devappmobile.flowfuel.station.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NominatimResultDTO {
    private String lat;
    private String lon;
    @JsonProperty("display_name")
    private String displayName;
}
