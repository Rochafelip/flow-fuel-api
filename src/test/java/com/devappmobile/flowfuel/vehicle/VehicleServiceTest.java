package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.common.PageResponseDTO;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.storage.StorageService;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.dto.PhotoUploadResponse;
import com.devappmobile.flowfuel.vehicle.dto.VehicleRequestDTO;
import com.devappmobile.flowfuel.vehicle.dto.VehicleResponseDTO;
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
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    @Mock private VehicleRepository vehicleRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthorizationHelper authorizationHelper;
    @Mock private StorageService storageService;

    @InjectMocks private VehicleService vehicleService;

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
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCapacity(55);
        vehicle.setType("Carro");
        vehicle.setIsActive(true);
    }

    // --- createVehicle ---

    @Test
    void createVehicle_dadosValidos_retornaVeiculoSalvo() {
        VehicleRequestDTO dto = new VehicleRequestDTO();
        dto.setType("Carro");
        dto.setEnergyType(EnergyType.COMBUSTION);
        dto.setCurrentKm(5000);
        dto.setCapacity(50);

        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(inv -> {
            Vehicle v = inv.getArgument(0);
            v.setId(99L);
            return v;
        });

        VehicleResponseDTO response = vehicleService.createVehicle(owner, dto);

        assertThat(response.getId()).isEqualTo(99L);
        assertThat(response.getType()).isEqualTo("Carro");
    }

    // --- getUserVehicles ---

    @Test
    void getUserVehicles_retornaPaginaDoUsuario() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Vehicle> page = new PageImpl<>(List.of(vehicle), pageable, 1);
        when(vehicleRepository.findByUserId(1L, pageable)).thenReturn(page);

        PageResponseDTO<VehicleResponseDTO> response = vehicleService.getUserVehicles(owner, pageable);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getTotalElements()).isEqualTo(1);
    }

    // --- getActiveVehicle ---

    @Test
    void getActiveVehicle_quandoExiste_retornaVeiculo() {
        when(vehicleRepository.findByUserId(1L)).thenReturn(List.of(vehicle));

        VehicleResponseDTO response = vehicleService.getActiveVehicle(owner);

        assertThat(response.getId()).isEqualTo(10L);
    }

    @Test
    void getActiveVehicle_quandoNenhumAtivo_lancaResourceNotFound() {
        vehicle.setIsActive(false);
        when(vehicleRepository.findByUserId(1L)).thenReturn(List.of(vehicle));

        assertThatThrownBy(() -> vehicleService.getActiveVehicle(owner))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- getVehicleById ---

    @Test
    void getVehicleById_comFotoSalva_retornaInternalUrl() {
        vehicle.setPhoto("vehicle_photos/10_foto.jpg");
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        VehicleResponseDTO response = vehicleService.getVehicleById(owner, 10L);

        assertThat(response.getPhoto()).isEqualTo("/vehicles/10/photo");
    }

    @Test
    void getVehicleById_semFoto_retornaPhotoNull() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        VehicleResponseDTO response = vehicleService.getVehicleById(owner, 10L);

        assertThat(response.getPhoto()).isNull();
    }

    @Test
    void getVehicleById_donoCorreto_retornaVeiculo() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        VehicleResponseDTO response = vehicleService.getVehicleById(owner, 10L);

        assertThat(response.getId()).isEqualTo(10L);
    }

    @Test
    void getVehicleById_usuarioNaoEDono_lancaForbidden() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

        assertThatThrownBy(() -> vehicleService.getVehicleById(otherUser, 10L))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void getVehicleById_veiculoInexistente_lancaResourceNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleService.getVehicleById(owner, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- updateOdometer ---

    @Test
    void updateOdometer_valorMaior_atualizaComSucesso() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleRepository.save(any())).thenReturn(vehicle);

        VehicleResponseDTO response = vehicleService.updateOdometer(owner, 10L, 2000);

        assertThat(response.getCurrentKm()).isEqualTo(2000);
        assertThat(vehicle.getCurrentKm()).isEqualTo(2000);
    }

    @Test
    void updateOdometer_valorMenorQueAtual_lancaBusinessRule() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> vehicleService.updateOdometer(owner, 10L, 500))
                .isInstanceOf(BusinessRuleException.class);
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void updateOdometer_convidadoComShareAtivo_atualiza() {
        User convidado = new User("convidado@test.com", "hash", "Convidado");
        convidado.setId(2L);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VehicleResponseDTO response = vehicleService.updateOdometer(convidado, 10L, 1050);

        assertThat(response.getCurrentKm()).isEqualTo(1050);
        verify(authorizationHelper).ensureOwnsOrHasGuestAccess(convidado, vehicle);
    }

    @Test
    void updateOdometer_usuarioSemAcesso_lancaForbidden() {
        User semAcesso = new User("semacesso@test.com", "hash", "Sem Acesso");
        semAcesso.setId(3L);

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário nem está compartilhado com ele"))
                .when(authorizationHelper).ensureOwnsOrHasGuestAccess(semAcesso, vehicle);

        assertThatThrownBy(() -> vehicleService.updateOdometer(semAcesso, 10L, 1050))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(vehicleRepository, never()).save(any());
    }

    // --- setActiveVehicle ---

    @Test
    void setActiveVehicle_desativaOutrosEAtivaSelecionado() {
        Vehicle v2 = new Vehicle();
        v2.setId(20L);
        v2.setUser(owner);
        v2.setIsActive(false);

        when(vehicleRepository.findById(20L)).thenReturn(Optional.of(v2));
        when(vehicleRepository.findByUserId(1L)).thenReturn(List.of(vehicle, v2));
        when(vehicleRepository.saveAll(any())).thenReturn(List.of(vehicle, v2));

        vehicleService.setActiveVehicle(owner, 20L);

        assertThat(vehicle.getIsActive()).isFalse();
        assertThat(v2.getIsActive()).isTrue();
    }

    // --- deleteVehicle ---

    @Test
    void deleteVehicle_donoCorreto_deleta() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        vehicleService.deleteVehicle(owner, 10L);

        verify(vehicleRepository).deleteById(10L);
    }

    @Test
    void deleteVehicle_usuarioNaoEDono_lancaForbiddenSemDeletar() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

        assertThatThrownBy(() -> vehicleService.deleteVehicle(otherUser, 10L))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(vehicleRepository, never()).deleteById(any());
    }

    // --- uploadPhoto ---

    @Test
    void uploadPhoto_arquivoAusente_lancaBusinessRule() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        MockMultipartFile arquivoVazio = new MockMultipartFile("file", "", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> vehicleService.uploadPhoto(owner, 10L, arquivoVazio))
                .isInstanceOf(BusinessRuleException.class);
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void uploadPhoto_tipoInvalido_lancaBusinessRule() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        MockMultipartFile arquivo = new MockMultipartFile("file", "foto.gif", "image/gif", new byte[100]);

        assertThatThrownBy(() -> vehicleService.uploadPhoto(owner, 10L, arquivo))
                .isInstanceOf(BusinessRuleException.class);
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void uploadPhoto_maiorQue5MB_lancaBusinessRule() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        byte[] arquivoGrande = new byte[6 * 1024 * 1024];
        MockMultipartFile arquivo = new MockMultipartFile("file", "foto.jpg", "image/jpeg", arquivoGrande);

        assertThatThrownBy(() -> vehicleService.uploadPhoto(owner, 10L, arquivo))
                .isInstanceOf(BusinessRuleException.class);
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void uploadPhoto_donoDiferente_lancaForbidden() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

        MockMultipartFile arquivo = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);

        assertThatThrownBy(() -> vehicleService.uploadPhoto(otherUser, 10L, arquivo))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void uploadPhoto_veiculoInexistente_lancaResourceNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        MockMultipartFile arquivo = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);

        assertThatThrownBy(() -> vehicleService.uploadPhoto(owner, 99L, arquivo))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void uploadPhoto_imagemValida_salvaChaveERetornaInternalUrl() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleRepository.save(any())).thenReturn(vehicle);

        MockMultipartFile arquivo = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);

        PhotoUploadResponse response = vehicleService.uploadPhoto(owner, 10L, arquivo);

        assertThat(response).isNotNull();
        assertThat(response.getInternalUrl()).isEqualTo("/vehicles/10/photo");
        assertThat(vehicle.getPhoto()).isEqualTo("vehicle_photos/10_foto.jpg");
        verify(storageService).upload(eq(arquivo), eq("vehicle_photos/10_foto.jpg"));
    }

    // --- getPhoto ---

    @Test
    void getPhoto_semFoto_retorna204() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        org.springframework.http.ResponseEntity<Void> response = vehicleService.getPhoto(owner, 10L);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.NO_CONTENT);
        verify(storageService, never()).publicUrl(any());
    }

    @Test
    void getPhoto_comFoto_retorna302ComLocation() {
        vehicle.setPhoto("vehicle_photos/10_foto.jpg");
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(storageService.publicUrl("vehicle_photos/10_foto.jpg"))
                .thenReturn("https://pub-test.r2.dev/vehicle_photos/10_foto.jpg");

        org.springframework.http.ResponseEntity<Void> response = vehicleService.getPhoto(owner, 10L);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .isEqualTo(java.net.URI.create("https://pub-test.r2.dev/vehicle_photos/10_foto.jpg"));
    }

    @Test
    void getPhoto_donoDiferente_lancaForbidden() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

        assertThatThrownBy(() -> vehicleService.getPhoto(otherUser, 10L))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void getPhoto_veiculoInexistente_lancaResourceNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleService.getPhoto(owner, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- removePhoto ---

    @Test
    void removePhoto_comFoto_deletaDoStorageEZeraCampo() {
        vehicle.setPhoto("vehicle_photos/10_foto.jpg");
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleRepository.save(any())).thenReturn(vehicle);

        vehicleService.removePhoto(owner, 10L);

        verify(storageService).delete("vehicle_photos/10_foto.jpg");
        assertThat(vehicle.getPhoto()).isNull();
    }

    @Test
    void removePhoto_semFoto_naoChamaStorageDelete() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        vehicleService.removePhoto(owner, 10L);

        verify(storageService, never()).delete(any());
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void removePhoto_donoDiferente_lancaForbidden() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

        assertThatThrownBy(() -> vehicleService.removePhoto(otherUser, 10L))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void removePhoto_veiculoInexistente_lancaResourceNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleService.removePhoto(owner, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
