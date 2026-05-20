package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/vehicle/{vehicleId}")
    public ResponseEntity<DashboardDTO> getVehicleDashboard(
            @AuthenticationPrincipal User user,
            @PathVariable Long vehicleId) {
        return dashboardService.getVehicleDashboard(user, vehicleId);
    }
}
