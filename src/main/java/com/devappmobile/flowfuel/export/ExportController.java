package com.devappmobile.flowfuel.export;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/exports")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    @GetMapping("/refuels")
    public ResponseEntity<byte[]> exportRefuels(
            @AuthenticationPrincipal User user,
            @RequestParam Long vehicleId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam String format) {
        ExportResult result = exportService.exportRefuels(user, vehicleId, startDate, endDate, format);
        return toResponse(result);
    }

    @GetMapping("/events")
    public ResponseEntity<byte[]> exportEvents(
            @AuthenticationPrincipal User user,
            @RequestParam Long vehicleId,
            @RequestParam(required = false) VehicleEventType type,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam String format) {
        ExportResult result = exportService.exportEvents(user, vehicleId, type, startDate, endDate, format);
        return toResponse(result);
    }

    private ResponseEntity<byte[]> toResponse(ExportResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(result.fileName()).build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(result.contentType()))
                .body(result.content());
    }
}
