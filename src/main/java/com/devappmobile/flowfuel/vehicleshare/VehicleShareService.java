package com.devappmobile.flowfuel.vehicleshare;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ConflictException;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.push.PushNotificationService;
import com.devappmobile.flowfuel.push.PushPayload;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareRequestDTO;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleShareService {

    private final VehicleShareRepository vehicleShareRepository;
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final AuthorizationHelper authorizationHelper;
    private final PushNotificationService pushNotificationService;

    @Transactional
    public VehicleShareResponseDTO create(User owner, VehicleShareRequestDTO request) {
        Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", request.getVehicleId()));
        authorizationHelper.ensureOwnsVehicle(owner, vehicle);

        if (request.getInviteeEmail().equalsIgnoreCase(owner.getEmail())) {
            throw new BusinessRuleException("Não é possível compartilhar o veículo com você mesmo");
        }

        User guest = userRepository.findByEmail(request.getInviteeEmail())
                .orElseThrow(() -> new ResourceNotFoundException("Usuário", request.getInviteeEmail()));

        if (vehicleShareRepository.existsByVehicleIdAndStatusIn(vehicle.getId(),
                List.of(VehicleShareStatus.PENDING, VehicleShareStatus.ACTIVE))) {
            throw new ConflictException("Veículo já possui um compartilhamento pendente ou ativo");
        }

        VehicleShare share = new VehicleShare();
        share.setVehicle(vehicle);
        share.setOwner(owner);
        share.setGuest(guest);
        share.setStatus(VehicleShareStatus.PENDING);
        share.setDurationDays(request.getDurationDays());
        VehicleShare saved = vehicleShareRepository.save(share);

        pushNotificationService.sendPushToUser(guest.getId(), new PushPayload(
                "Convite de veículo",
                "%s quer compartilhar o %s %s com você".formatted(owner.getName(), vehicle.getBrand(), vehicle.getModel()),
                "flowfuel://vehicle-share/" + saved.getId(),
                "vehicle_share_invite"));

        return VehicleShareResponseDTO.from(saved);
    }

    @Transactional
    public VehicleShareResponseDTO accept(User user, Long id) {
        VehicleShare share = vehicleShareRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Compartilhamento", id));
        ensureIsGuest(user, share);
        ensureIsPending(share);

        share.setStatus(VehicleShareStatus.ACTIVE);
        share.setRespondedAt(LocalDateTime.now());
        share.setExpiresAt(LocalDateTime.now().plusDays(share.getDurationDays()));

        return VehicleShareResponseDTO.from(vehicleShareRepository.save(share));
    }

    @Transactional
    public VehicleShareResponseDTO reject(User user, Long id) {
        VehicleShare share = vehicleShareRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Compartilhamento", id));
        ensureIsGuest(user, share);
        ensureIsPending(share);

        share.setStatus(VehicleShareStatus.REJECTED);
        share.setRespondedAt(LocalDateTime.now());

        return VehicleShareResponseDTO.from(vehicleShareRepository.save(share));
    }

    @Transactional
    public void revoke(User owner, Long id) {
        VehicleShare share = vehicleShareRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Compartilhamento", id));
        if (!share.getOwner().getId().equals(owner.getId())) {
            throw new ForbiddenOperationException("Compartilhamento não pertence ao usuário");
        }
        share.setStatus(VehicleShareStatus.REVOKED);
        share.setRespondedAt(LocalDateTime.now());
        vehicleShareRepository.save(share);
    }

    public VehicleShareResponseDTO getForVehicle(User user, Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));
        authorizationHelper.ensureOwnsVehicle(user, vehicle);

        return vehicleShareRepository
                .findFirstByVehicleIdAndStatusInOrderByCreatedAtDesc(vehicleId,
                        List.of(VehicleShareStatus.PENDING, VehicleShareStatus.ACTIVE))
                .map(VehicleShareResponseDTO::from)
                .orElse(null);
    }

    public List<VehicleShareResponseDTO> listPendingForGuest(User user) {
        return vehicleShareRepository.findByGuestIdAndStatus(user.getId(), VehicleShareStatus.PENDING)
                .stream()
                .map(VehicleShareResponseDTO::from)
                .toList();
    }

    public List<VehicleShareResponseDTO> listActiveForGuest(User user) {
        return vehicleShareRepository
                .findByGuestIdAndStatusAndExpiresAtAfter(user.getId(), VehicleShareStatus.ACTIVE, LocalDateTime.now())
                .stream()
                .map(VehicleShareResponseDTO::from)
                .toList();
    }

    private void ensureIsGuest(User user, VehicleShare share) {
        if (!share.getGuest().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Convite não pertence ao usuário");
        }
    }

    private void ensureIsPending(VehicleShare share) {
        if (share.getStatus() != VehicleShareStatus.PENDING) {
            throw new BusinessRuleException("Convite não está mais disponível");
        }
    }
}
