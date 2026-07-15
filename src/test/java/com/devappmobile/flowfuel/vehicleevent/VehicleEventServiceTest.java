package com.devappmobile.flowfuel.vehicleevent;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.common.PageResponseDTO;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleevent.dto.VehicleEventRequestDTO;
import com.devappmobile.flowfuel.vehicleevent.dto.VehicleEventResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleEventServiceTest {

    @Mock private VehicleEventRepository vehicleEventRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private AuthorizationHelper authorizationHelper;

    @InjectMocks private VehicleEventService vehicleEventService;

    private User owner;
    private User otherUser;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        owner = new User("owner@test.com", "hash", "Owner");
        owner.setId(1L);

        otherUser = new User("other@test.com", "hash", "Other");
        otherUser.setId(2L);

        vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setUser(owner);
        vehicle.setCurrentKm(1000);
    }

    private VehicleEventRequestDTO buildRequest() {
        VehicleEventRequestDTO dto = new VehicleEventRequestDTO();
        dto.setVehicleId(10L);
        dto.setType(VehicleEventType.MAINTENANCE);
        dto.setAmount(BigDecimal.valueOf(250.00));
        dto.setEventDate(LocalDate.of(2026, 1, 10));
        dto.setOdometer(1200);
        dto.setDescription("Troca de óleo");
        return dto;
    }

    private VehicleEvent buildEvent(Long id) {
        VehicleEvent event = new VehicleEvent();
        event.setId(id);
        event.setVehicle(vehicle);
        event.setType(VehicleEventType.MAINTENANCE);
        event.setAmount(BigDecimal.valueOf(250.00));
        event.setEventDate(LocalDate.of(2026, 1, 10));
        event.setOdometer(1200);
        event.setDescription("Troca de óleo");
        return event;
    }

    // --- create ---

    @Test
    void create_dadosValidos_retornaEvento() {
        VehicleEventRequestDTO dto = buildRequest();
        VehicleEvent saved = buildEvent(1L);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleEventRepository.save(any(VehicleEvent.class))).thenReturn(saved);

        VehicleEventResponseDTO response = vehicleEventService.create(owner, dto);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getVehicleId()).isEqualTo(10L);
        assertThat(response.getType()).isEqualTo(VehicleEventType.MAINTENANCE);
        assertThat(response.getAmount()).isEqualByComparingTo("250.00");
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void create_usuarioNaoEDono_lancaForbidden() {
        VehicleEventRequestDTO dto = buildRequest();
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário nem está compartilhado com ele"))
                .when(authorizationHelper).ensureOwnsOrHasGuestAccess(otherUser, vehicle);

        assertThatThrownBy(() -> vehicleEventService.create(otherUser, dto))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(vehicleEventRepository, never()).save(any());
    }

    @Test
    void create_veiculoInexistente_lancaResourceNotFound() {
        VehicleEventRequestDTO dto = buildRequest();
        when(vehicleRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleEventService.create(owner, dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_convidadoComShareAtivoECategoriaPermitida_criaEvento() {
        VehicleEventRequestDTO request = new VehicleEventRequestDTO();
        request.setVehicleId(10L);
        request.setType(VehicleEventType.FUEL);
        request.setAmount(BigDecimal.valueOf(150.00));
        request.setEventDate(LocalDate.now());

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleEventRepository.save(any(VehicleEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VehicleEventResponseDTO response = vehicleEventService.create(otherUser, request);

        assertThat(response.getType()).isEqualTo(VehicleEventType.FUEL);
        verify(authorizationHelper).ensureOwnsOrHasGuestAccess(otherUser, vehicle);
    }

    @Test
    void create_convidadoComShareAtivoECategoriaNaoPermitida_lancaForbidden() {
        VehicleEventRequestDTO request = new VehicleEventRequestDTO();
        request.setVehicleId(10L);
        request.setType(VehicleEventType.INSURANCE);
        request.setAmount(BigDecimal.valueOf(500.00));
        request.setEventDate(LocalDate.now());

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> vehicleEventService.create(otherUser, request))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(vehicleEventRepository, never()).save(any());
    }

    @Test
    void create_donoLancandoQualquerCategoria_naoRestringe() {
        VehicleEventRequestDTO request = new VehicleEventRequestDTO();
        request.setVehicleId(10L);
        request.setType(VehicleEventType.INSURANCE);
        request.setAmount(BigDecimal.valueOf(500.00));
        request.setEventDate(LocalDate.now());

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleEventRepository.save(any(VehicleEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VehicleEventResponseDTO response = vehicleEventService.create(owner, request);

        assertThat(response.getType()).isEqualTo(VehicleEventType.INSURANCE);
    }

    // --- getById ---

    @Test
    void getById_dadosValidos_retornaEvento() {
        VehicleEvent event = buildEvent(5L);
        when(vehicleEventRepository.findById(5L)).thenReturn(Optional.of(event));

        VehicleEventResponseDTO response = vehicleEventService.getById(owner, 5L);

        assertThat(response.getId()).isEqualTo(5L);
        assertThat(response.getVehicleId()).isEqualTo(10L);
    }

    @Test
    void getById_inexistente_lancaResourceNotFound() {
        when(vehicleEventRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleEventService.getById(owner, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_usuarioNaoEDono_lancaForbidden() {
        VehicleEvent event = buildEvent(5L);
        when(vehicleEventRepository.findById(5L)).thenReturn(Optional.of(event));
        doThrow(new ForbiddenOperationException("Evento não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsEvent(otherUser, event);

        assertThatThrownBy(() -> vehicleEventService.getById(otherUser, 5L))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    // --- update ---

    @Test
    void update_parcial_atualizaApenasCamposPresentes() {
        VehicleEvent event = buildEvent(5L);
        when(vehicleEventRepository.findById(5L)).thenReturn(Optional.of(event));
        when(vehicleEventRepository.save(any(VehicleEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        VehicleEventRequestDTO partial = new VehicleEventRequestDTO();
        partial.setAmount(BigDecimal.valueOf(300.00));
        partial.setDescription("Atualizado");

        VehicleEventResponseDTO response = vehicleEventService.update(owner, 5L, partial);

        assertThat(response.getAmount()).isEqualByComparingTo("300.00");
        assertThat(response.getDescription()).isEqualTo("Atualizado");
        assertThat(response.getType()).isEqualTo(VehicleEventType.MAINTENANCE);
        assertThat(response.getOdometer()).isEqualTo(1200);
        assertThat(response.getEventDate()).isEqualTo(LocalDate.of(2026, 1, 10));
    }

    @Test
    void update_usuarioNaoEDono_lancaForbidden() {
        VehicleEvent event = buildEvent(5L);
        when(vehicleEventRepository.findById(5L)).thenReturn(Optional.of(event));
        doThrow(new ForbiddenOperationException("Evento não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsEvent(otherUser, event);

        assertThatThrownBy(() -> vehicleEventService.update(otherUser, 5L, buildRequest()))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(vehicleEventRepository, never()).save(any());
    }

    @Test
    void update_naoAlteraCurrentKmDoVeiculo() {
        VehicleEvent event = buildEvent(5L);
        when(vehicleEventRepository.findById(5L)).thenReturn(Optional.of(event));
        when(vehicleEventRepository.save(any(VehicleEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        VehicleEventRequestDTO partial = new VehicleEventRequestDTO();
        partial.setOdometer(9999);

        vehicleEventService.update(owner, 5L, partial);

        verify(vehicleRepository, never()).save(any());
        assertThat(vehicle.getCurrentKm()).isEqualTo(1000);
    }

    // --- delete ---

    @Test
    void delete_dadosValidos_removeEvento() {
        VehicleEvent event = buildEvent(5L);
        when(vehicleEventRepository.findById(5L)).thenReturn(Optional.of(event));

        vehicleEventService.delete(owner, 5L);

        verify(vehicleEventRepository).deleteById(5L);
    }

    @Test
    void delete_usuarioNaoEDono_lancaForbidden() {
        VehicleEvent event = buildEvent(5L);
        when(vehicleEventRepository.findById(5L)).thenReturn(Optional.of(event));
        doThrow(new ForbiddenOperationException("Evento não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsEvent(otherUser, event);

        assertThatThrownBy(() -> vehicleEventService.delete(otherUser, 5L))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(vehicleEventRepository, never()).deleteById(any());
    }

    // --- getVehicleEvents ---

    @Test
    void getVehicleEvents_semFiltros_usaQueryBase() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<VehicleEvent> page = new PageImpl<>(List.of(buildEvent(1L)));

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleEventRepository
                .findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(10L, pageable))
                .thenReturn(page);

        PageResponseDTO<VehicleEventResponseDTO> result =
                vehicleEventService.getVehicleEvents(owner, 10L, null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(vehicleEventRepository)
                .findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(10L, pageable);
        verify(vehicleEventRepository, never())
                .findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(any(), any(), any());
    }

    @Test
    void getVehicleEvents_apenasType_usaQueryPorTipo() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<VehicleEvent> page = new PageImpl<>(List.of(buildEvent(1L)));

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleEventRepository
                .findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(
                        eq(10L), eq(VehicleEventType.MAINTENANCE), eq(pageable)))
                .thenReturn(page);

        PageResponseDTO<VehicleEventResponseDTO> result = vehicleEventService.getVehicleEvents(
                owner, 10L, VehicleEventType.MAINTENANCE, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(vehicleEventRepository)
                .findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(
                        10L, VehicleEventType.MAINTENANCE, pageable);
    }

    @Test
    void getVehicleEvents_apenasDatas_usaQueryPorPeriodo() {
        Pageable pageable = PageRequest.of(0, 10);
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        Page<VehicleEvent> page = new PageImpl<>(List.of(buildEvent(1L)));

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleEventRepository
                .findByVehicleIdAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                        10L, start, end, pageable))
                .thenReturn(page);

        PageResponseDTO<VehicleEventResponseDTO> result =
                vehicleEventService.getVehicleEvents(owner, 10L, null, start, end, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(vehicleEventRepository)
                .findByVehicleIdAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                        10L, start, end, pageable);
    }

    @Test
    void getVehicleEvents_typeEDatas_usaQueryCombinada() {
        Pageable pageable = PageRequest.of(0, 10);
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        Page<VehicleEvent> page = new PageImpl<>(List.of(buildEvent(1L)));

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleEventRepository
                .findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                        10L, VehicleEventType.MAINTENANCE, start, end, pageable))
                .thenReturn(page);

        PageResponseDTO<VehicleEventResponseDTO> result = vehicleEventService.getVehicleEvents(
                owner, 10L, VehicleEventType.MAINTENANCE, start, end, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(vehicleEventRepository)
                .findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                        10L, VehicleEventType.MAINTENANCE, start, end, pageable);
    }

    @Test
    void getVehicleEvents_usuarioNaoEDono_lancaForbidden() {
        Pageable pageable = PageRequest.of(0, 10);
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

        assertThatThrownBy(() -> vehicleEventService.getVehicleEvents(
                otherUser, 10L, null, null, null, pageable))
                .isInstanceOf(ForbiddenOperationException.class);
    }
}
