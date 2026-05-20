package com.devappmobile.flowfuel.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/vehicle/{vehicleId}")
    public ResponseEntity<DashboardDTO> getVehicleDashboard(
            @PathVariable Long vehicleId) {

        DashboardDTO dashboard =
                dashboardService.getVehicleDashboard(vehicleId);

        return ResponseEntity.ok(dashboard);
    }
}
