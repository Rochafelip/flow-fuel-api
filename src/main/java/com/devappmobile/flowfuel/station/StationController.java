package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import com.devappmobile.flowfuel.user.User;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/stations")
@RequiredArgsConstructor
@Validated
public class StationController {

    private final StationService stationService;

    @GetMapping("/nearby")
    public List<StationResponseDTO> getNearbyStations(
            @AuthenticationPrincipal User user,
            @RequestParam @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @RequestParam(defaultValue = "5000") @Positive Integer radius) {
        return stationService.findNearby(user.getId(), lat, lng, radius);
    }
}
