package com.devappmobile.flowfuel.vehicleshare;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.push.PushNotificationService;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareRequestDTO;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VehicleShareServiceTest {

    @Mock
    private VehicleShareRepository vehicleShareRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthorizationHelper authorizationHelper;
    @Mock
    private PushNotificationService pushNotificationService;

    @InjectMocks
    private VehicleShareService vehicleShareService;

    private User owner;
    private User guest;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@test.com");
        owner.setName("Dono");

        guest = new User();
        guest.setId(2L);
        guest.setEmail("guest@test.com");
        guest.setName("Convidado");

        vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setUser(owner);
        vehicle.setBrand("Toyota");
        vehicle.setModel("Corolla");
    }

    private VehicleShareRequestDTO requestValido() {
        VehicleShareRequestDTO request = new VehicleShareRequestDTO();
        request.setVehicleId(10L);
        request.setInviteeEmail("guest@test.com");
        request.setDurationDays(3);
        return request;
    }

    @Test
    void create_convitesValido_criaComStatusPendingEEnviaPush() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(userRepository.findByEmail("guest@test.com")).thenReturn(Optional.of(guest));
        when(vehicleShareRepository.existsByVehicleIdAndStatusIn(eq(10L), any())).thenReturn(false);
        when(vehicleShareRepository.save(any(VehicleShare.class)))
                .thenAnswer(invocation -> {
                    VehicleShare share = invocation.getArgument(0);
                    share.setId(100L);
                    return share;
                });

        VehicleShareResponseDTO response = vehicleShareService.create(owner, requestValido());

        assertThat(response.getStatus()).isEqualTo(VehicleShareStatus.PENDING);
        assertThat(response.getGuestId()).isEqualTo(2L);
        verify(pushNotificationService).sendPushToUser(eq(2L), any());
    }

    @Test
    void create_convidarASiMesmo_lancaBusinessRuleException() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        VehicleShareRequestDTO request = requestValido();
        request.setInviteeEmail("owner@test.com");

        assertThatThrownBy(() -> vehicleShareService.create(owner, request))
                .isInstanceOf(BusinessRuleException.class);

        verify(vehicleShareRepository, never()).save(any());
    }

    @Test
    void create_emailNaoCadastrado_lancaResourceNotFoundException() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(userRepository.findByEmail("guest@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleShareService.create(owner, requestValido()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_veiculoJaTemSharePendenteOuAtivo_lancaConflictException() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(userRepository.findByEmail("guest@test.com")).thenReturn(Optional.of(guest));
        when(vehicleShareRepository.existsByVehicleIdAndStatusIn(eq(10L), any())).thenReturn(true);

        assertThatThrownBy(() -> vehicleShareService.create(owner, requestValido()))
                .isInstanceOf(ConflictException.class);

        verify(vehicleShareRepository, never()).save(any());
    }

    private VehicleShare shareExistente(VehicleShareStatus status) {
        VehicleShare share = new VehicleShare();
        share.setId(100L);
        share.setVehicle(vehicle);
        share.setOwner(owner);
        share.setGuest(guest);
        share.setStatus(status);
        share.setDurationDays(3);
        return share;
    }

    @Test
    void accept_convitePendenteEGuestCorreto_ativaECalculaExpiresAt() {
        VehicleShare share = shareExistente(VehicleShareStatus.PENDING);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));
        when(vehicleShareRepository.save(any(VehicleShare.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VehicleShareResponseDTO response = vehicleShareService.accept(guest, 100L);

        assertThat(response.getStatus()).isEqualTo(VehicleShareStatus.ACTIVE);
        assertThat(response.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(2));
        assertThat(response.getExpiresAt()).isBefore(LocalDateTime.now().plusDays(4));
    }

    @Test
    void accept_naoEhOGuestDoConvite_lancaForbidden() {
        VehicleShare share = shareExistente(VehicleShareStatus.PENDING);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> vehicleShareService.accept(owner, 100L))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void accept_conviteJaRespondido_lancaBusinessRuleException() {
        VehicleShare share = shareExistente(VehicleShareStatus.ACTIVE);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> vehicleShareService.accept(guest, 100L))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void reject_convitePendenteEGuestCorreto_marcaRejected() {
        VehicleShare share = shareExistente(VehicleShareStatus.PENDING);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));
        when(vehicleShareRepository.save(any(VehicleShare.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VehicleShareResponseDTO response = vehicleShareService.reject(guest, 100L);

        assertThat(response.getStatus()).isEqualTo(VehicleShareStatus.REJECTED);
    }

    @Test
    void reject_naoEhOGuestDoConvite_lancaForbidden() {
        VehicleShare share = shareExistente(VehicleShareStatus.PENDING);
        when(vehicleShareRepository.findById(100L)).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> vehicleShareService.reject(owner, 100L))
                .isInstanceOf(ForbiddenOperationException.class);
    }
}
