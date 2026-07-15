package com.devappmobile.flowfuel.vehicleshare;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class VehicleShareRepositoryTest {

    @Autowired
    private VehicleShareRepository vehicleShareRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    private User criarUsuario(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPassword("hash");
        user.setName("User " + email);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private Vehicle criarVeiculo(User owner) {
        Vehicle vehicle = new Vehicle();
        vehicle.setType("CARRO");
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCurrentKm(1000);
        vehicle.setCapacity(50);
        vehicle.setUser(owner);
        return vehicleRepository.save(vehicle);
    }

    private VehicleShare criarShare(Vehicle vehicle, User owner, User guest, VehicleShareStatus status, LocalDateTime expiresAt) {
        VehicleShare share = new VehicleShare();
        share.setVehicle(vehicle);
        share.setOwner(owner);
        share.setGuest(guest);
        share.setStatus(status);
        share.setDurationDays(1);
        share.setExpiresAt(expiresAt);
        return vehicleShareRepository.save(share);
    }

    @Test
    void existsByVehicleIdAndStatusIn_comSharePendente_retornaTrue() {
        User owner = criarUsuario("repo-owner-1@test.com");
        User guest = criarUsuario("repo-guest-1@test.com");
        Vehicle vehicle = criarVeiculo(owner);
        criarShare(vehicle, owner, guest, VehicleShareStatus.PENDING, null);

        boolean existe = vehicleShareRepository.existsByVehicleIdAndStatusIn(
                vehicle.getId(), List.of(VehicleShareStatus.PENDING, VehicleShareStatus.ACTIVE));

        assertThat(existe).isTrue();
    }

    @Test
    void existsByVehicleIdAndStatusIn_semShare_retornaFalse() {
        User owner = criarUsuario("repo-owner-2@test.com");
        Vehicle vehicle = criarVeiculo(owner);

        boolean existe = vehicleShareRepository.existsByVehicleIdAndStatusIn(
                vehicle.getId(), List.of(VehicleShareStatus.PENDING, VehicleShareStatus.ACTIVE));

        assertThat(existe).isFalse();
    }

    @Test
    void findFirstByVehicleIdAndStatusInOrderByCreatedAtDesc_retornaMaisRecente() {
        User owner = criarUsuario("repo-owner-3@test.com");
        User guest = criarUsuario("repo-guest-3@test.com");
        Vehicle vehicle = criarVeiculo(owner);
        criarShare(vehicle, owner, guest, VehicleShareStatus.REJECTED, null);
        VehicleShare ativo = criarShare(vehicle, owner, guest, VehicleShareStatus.ACTIVE, LocalDateTime.now().plusDays(1));

        Optional<VehicleShare> encontrado = vehicleShareRepository
                .findFirstByVehicleIdAndStatusInOrderByCreatedAtDesc(
                        vehicle.getId(), List.of(VehicleShareStatus.PENDING, VehicleShareStatus.ACTIVE));

        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getId()).isEqualTo(ativo.getId());
    }

    @Test
    void findByGuestIdAndStatus_retornaSharesDoConvidadoComStatus() {
        User owner = criarUsuario("repo-owner-4@test.com");
        User guest = criarUsuario("repo-guest-4@test.com");
        Vehicle vehicle = criarVeiculo(owner);
        criarShare(vehicle, owner, guest, VehicleShareStatus.PENDING, null);

        List<VehicleShare> pendentes = vehicleShareRepository.findByGuestIdAndStatus(
                guest.getId(), VehicleShareStatus.PENDING);

        assertThat(pendentes).hasSize(1);
    }

    @Test
    void findByGuestIdAndStatusAndExpiresAtAfter_ignoraShareExpirado() {
        User owner = criarUsuario("repo-owner-5@test.com");
        User guest = criarUsuario("repo-guest-5@test.com");
        Vehicle vehicle = criarVeiculo(owner);
        criarShare(vehicle, owner, guest, VehicleShareStatus.ACTIVE, LocalDateTime.now().minusHours(1));

        List<VehicleShare> ativos = vehicleShareRepository.findByGuestIdAndStatusAndExpiresAtAfter(
                guest.getId(), VehicleShareStatus.ACTIVE, LocalDateTime.now());

        assertThat(ativos).isEmpty();
    }

    @Test
    void existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter_shareAtivoDentroDoPrazo_retornaTrue() {
        User owner = criarUsuario("repo-owner-6@test.com");
        User guest = criarUsuario("repo-guest-6@test.com");
        Vehicle vehicle = criarVeiculo(owner);
        criarShare(vehicle, owner, guest, VehicleShareStatus.ACTIVE, LocalDateTime.now().plusDays(1));

        boolean temAcesso = vehicleShareRepository.existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter(
                vehicle.getId(), guest.getId(), VehicleShareStatus.ACTIVE, LocalDateTime.now());

        assertThat(temAcesso).isTrue();
    }
}
