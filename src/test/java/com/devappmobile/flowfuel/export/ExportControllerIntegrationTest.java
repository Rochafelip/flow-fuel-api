package com.devappmobile.flowfuel.export;

import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventRepository;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventType;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ExportControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private RefuelRepository refuelRepository;
    @Autowired private VehicleEventRepository vehicleEventRepository;
    @Autowired private ObjectMapper objectMapper;

    private Vehicle vehicle;
    private String ownerToken;

    @BeforeEach
    void setUp() throws Exception {
        vehicleEventRepository.deleteAll();
        refuelRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();

        ownerToken = registerAndLogin("owner@test.com");

        User owner = userRepository.findByEmail("owner@test.com").orElseThrow();
        vehicle = new Vehicle();
        vehicle.setType("car");
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCurrentKm(1500);
        vehicle.setCapacity(50);
        vehicle.setBrand("Toyota");
        vehicle.setModel("Corolla");
        vehicle.setUser(owner);
        vehicle = vehicleRepository.save(vehicle);

        Refuel refuel = new Refuel();
        refuel.setVehicle(vehicle);
        refuel.setOdometer(1500);
        refuel.setEnergyAmount(BigDecimal.valueOf(40));
        refuel.setPricePerUnit(BigDecimal.valueOf(5.89));
        refuel.setRefuelType(RefuelType.FUEL);
        refuel.setRefuelDate(LocalDateTime.of(2026, 6, 1, 10, 0));
        refuelRepository.save(refuel);

        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);
        event.setType(VehicleEventType.MAINTENANCE);
        event.setAmount(BigDecimal.valueOf(150));
        event.setEventDate(LocalDate.of(2026, 6, 1));
        vehicleEventRepository.save(event);
    }

    private String registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123","name":"User"}
                        """.formatted(email)));
        userRepository.findByEmail(email).ifPresent(u -> {
            u.setStatus(UserStatus.ACTIVE);
            userRepository.save(u);
        });
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123"}
                        """.formatted(email)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void exportRefuels_csv_retornaArquivoComHeadersCorretos() throws Exception {
        mockMvc.perform(get("/api/v1/exports/refuels")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "csv")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("flowfuel-refuels-toyota-corolla-")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))));
    }

    @Test
    void exportEvents_xlsx_retornaArquivoComContentTypeCorreto() throws Exception {
        mockMvc.perform(get("/api/v1/exports/events")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "xlsx")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void exportRefuels_veiculoDeOutroUsuario_retorna403() throws Exception {
        String otherToken = registerAndLogin("other@test.com");

        mockMvc.perform(get("/api/v1/exports/refuels")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "csv")
                .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportRefuels_veiculoInexistente_retorna404() throws Exception {
        mockMvc.perform(get("/api/v1/exports/refuels")
                .param("vehicleId", "999999")
                .param("format", "csv")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportRefuels_formatoInvalido_retorna400() throws Exception {
        mockMvc.perform(get("/api/v1/exports/refuels")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "pdf")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportEvents_datasInvertidas_retorna400() throws Exception {
        mockMvc.perform(get("/api/v1/exports/events")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "csv")
                .param("startDate", "2026-12-31")
                .param("endDate", "2026-01-01")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportEvents_semAutenticacao_retorna401() throws Exception {
        mockMvc.perform(get("/api/v1/exports/events")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "csv"))
                .andExpect(status().isUnauthorized());
    }
}
