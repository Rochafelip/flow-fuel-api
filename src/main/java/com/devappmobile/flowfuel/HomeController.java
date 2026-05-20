package com.devappmobile.flowfuel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "Health", description = "Verificação de disponibilidade da API")
public class HomeController {

    @GetMapping("/")
    @Operation(summary = "Health check", description = "Retorna status online da API e timestamp atual")
    public Map<String, Object> home() {
        return Map.of(
                "status", "online",
                "message", "API está online",
                "timestamp", System.currentTimeMillis());
    }
}
