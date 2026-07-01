package com.devappmobile.flowfuel.station.client;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class OverpassResponseDTO {

    private List<Element> elements;

    @Getter
    @Setter
    public static class Element {
        private String type;
        private Long id;
        private Double lat;
        private Double lon;
        private Center center;
        private Map<String, String> tags;
    }

    @Getter
    @Setter
    public static class Center {
        private Double lat;
        private Double lon;
    }
}
