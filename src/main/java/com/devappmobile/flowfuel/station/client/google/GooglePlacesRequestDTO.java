package com.devappmobile.flowfuel.station.client.google;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GooglePlacesRequestDTO {
    private LocationRestriction locationRestriction;
    private List<String> includedTypes;
    private Integer maxResultCount;
    private String rankPreference;
}
