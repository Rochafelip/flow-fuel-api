# Upload de foto do veículo — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar `POST /api/v1/vehicles/{id}/photo` para upload de foto do veículo, espelhando exatamente o padrão real (não o descrito na spec) do upload de foto de perfil de usuário.

**Architecture:** A foto é validada (tipo JPEG/PNG/WEBP, tamanho ≤ 5MB) e persistida via o `StorageService` já existente (implementação real: `PostgresStorageService`, que redimensiona a imagem para 512×512 JPEG e grava bytes na tabela `stored_files` — **não é S3/Backblaze B2**, apesar do que a spec original descrevia). A chave retornada pelo storage é salva em `Vehicle.photo`. A resposta e o `VehicleResponseDTO.photo` expõem uma `internalUrl` (`/vehicles/{id}/photo`), no mesmo formato do endpoint de foto de perfil — **sem `signedUrl`**, porque esse campo não existe em nenhum lugar do código real (confirmado por investigação: `UploadResponse` do perfil só tem `internalUrl`).

**Tech Stack:** Java 21, Spring Boot, Maven (`./mvnw`), JUnit 5 + Mockito (testes unitários), MockMvc (testes de integração).

---

## Pontos de atenção (divergências entre a spec e o código real)

1. **Storage não é S3/Backblaze B2.** A spec (`docs/superpowers/specs/2026-07-03-upload-foto-veiculo-design.md`) descreve S3/B2, mas o upload de foto de perfil (o padrão que devemos espelhar) usa `PostgresStorageService`, que grava bytes redimensionados na tabela `stored_files`. Este plano segue o código real.
2. **Não existe `signedUrl` em lugar nenhum.** A spec pede um campo `signedUrl` "mesma janela de expiração usada no perfil (15 min)" — isso não existe: nem `UploadResponse` do perfil, nem `UserResponseDTO`, têm esse campo. A resposta deste novo endpoint terá só `internalUrl`, assim como o de perfil.
3. **Limite de tamanho do servlet == limite de negócio (5MB).** Como `spring.servlet.multipart.max-file-size=5MB` (mesmo valor da regra de negócio), na prática um arquivo real acima de 5MB é rejeitado pelo Spring *antes* de chegar no service (gera `MaxUploadSizeExceededException`), e a validação de tamanho no service só é exercitável via teste unitário com mock. Isso é o mesmo comportamento (e a mesma lacuna) já observado no upload de foto de perfil.

---

## File Structure

- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleController.java` — novo endpoint `POST /{id}/photo`.
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java` — método `uploadPhoto`, validações, injeta `StorageService`.
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/dto/VehicleResponseDTO.java` — `from()` passa a converter a chave de storage salva em `photo` para uma `internalUrl`.
- Create: `src/main/java/com/devappmobile/flowfuel/vehicle/dto/PhotoUploadResponse.java` — DTO de resposta `{ internalUrl }`.
- Modify: `src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java` — handler para `MaxUploadSizeExceededException` → `400 BUSINESS_RULE_VIOLATED`.
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java` — testes unitários de `uploadPhoto`.
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleControllerIntegrationTest.java` — testes de integração do endpoint.
- Modify: `docs/spec/openapi.yaml` — documenta o novo path.

---

### Task 1: `PhotoUploadResponse` DTO

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/vehicle/dto/PhotoUploadResponse.java`

- [x] **Step 1: Criar o DTO**

```java
package com.devappmobile.flowfuel.vehicle.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PhotoUploadResponse {
    private String internalUrl; // /vehicles/{id}/photo
}
```

- [x] **Step 2: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicle/dto/PhotoUploadResponse.java
git commit -m "feat(vehicle): add PhotoUploadResponse dto"
```

---

### Task 2: `VehicleService.uploadPhoto` + validações

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`
- Test: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java`

- [x] **Step 1: Escrever os testes que falham (unitários, com mocks)**

Adicionar ao final de `VehicleServiceTest.java` (dentro da classe, após os testes existentes; adicionar `@Mock private StorageService storageService;` ao topo da classe e os imports necessários):

```java
// no topo da classe, junto aos outros @Mock:
@Mock private com.devappmobile.flowfuel.storage.StorageService storageService;
```

```java
// --- uploadPhoto ---

@Test
void uploadPhoto_arquivoAusente_lancaBusinessRule() {
    when(vehicleRepository.findById(1L)).thenReturn(Optional.of(vehicle));

    org.springframework.mock.web.MockMultipartFile arquivoVazio =
            new org.springframework.mock.web.MockMultipartFile("file", "", "image/jpeg", new byte[0]);

    assertThatThrownBy(() -> vehicleService.uploadPhoto(owner, 1L, arquivoVazio))
            .isInstanceOf(BusinessRuleException.class);
    verify(vehicleRepository, never()).save(any());
}

@Test
void uploadPhoto_tipoInvalido_lancaBusinessRule() {
    when(vehicleRepository.findById(1L)).thenReturn(Optional.of(vehicle));

    org.springframework.mock.web.MockMultipartFile arquivo =
            new org.springframework.mock.web.MockMultipartFile("file", "foto.gif", "image/gif", new byte[100]);

    assertThatThrownBy(() -> vehicleService.uploadPhoto(owner, 1L, arquivo))
            .isInstanceOf(BusinessRuleException.class);
    verify(vehicleRepository, never()).save(any());
}

@Test
void uploadPhoto_maiorQue5MB_lancaBusinessRule() {
    when(vehicleRepository.findById(1L)).thenReturn(Optional.of(vehicle));

    byte[] arquivoGrande = new byte[6 * 1024 * 1024];
    org.springframework.mock.web.MockMultipartFile arquivo =
            new org.springframework.mock.web.MockMultipartFile("file", "foto.jpg", "image/jpeg", arquivoGrande);

    assertThatThrownBy(() -> vehicleService.uploadPhoto(owner, 1L, arquivo))
            .isInstanceOf(BusinessRuleException.class);
    verify(vehicleRepository, never()).save(any());
}

@Test
void uploadPhoto_donoDiferente_lancaForbidden() {
    when(vehicleRepository.findById(1L)).thenReturn(Optional.of(vehicle));
    doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
            .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

    org.springframework.mock.web.MockMultipartFile arquivo =
            new org.springframework.mock.web.MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);

    assertThatThrownBy(() -> vehicleService.uploadPhoto(otherUser, 1L, arquivo))
            .isInstanceOf(ForbiddenOperationException.class);
}

@Test
void uploadPhoto_veiculoInexistente_lancaResourceNotFound() {
    when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

    org.springframework.mock.web.MockMultipartFile arquivo =
            new org.springframework.mock.web.MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);

    assertThatThrownBy(() -> vehicleService.uploadPhoto(owner, 99L, arquivo))
            .isInstanceOf(ResourceNotFoundException.class);
}

@Test
void uploadPhoto_imagemValida_salvaChaveERetornaInternalUrl() {
    when(vehicleRepository.findById(1L)).thenReturn(Optional.of(vehicle));
    when(vehicleRepository.save(any())).thenReturn(vehicle);

    org.springframework.mock.web.MockMultipartFile arquivo =
            new org.springframework.mock.web.MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);

    PhotoUploadResponse response = vehicleService.uploadPhoto(owner, 1L, arquivo);

    assertThat(response).isNotNull();
    assertThat(response.getInternalUrl()).isEqualTo("/vehicles/1/photo");
    assertThat(vehicle.getPhoto()).isEqualTo("vehicle_photos/1_foto.jpg");
    verify(storageService).upload(eq(arquivo), eq("vehicle_photos/1_foto.jpg"));
}
```

Adicionar os imports que faltam no topo do arquivo de teste:
```java
import com.devappmobile.flowfuel.vehicle.dto.PhotoUploadResponse;
```
(`BusinessRuleException`, `ForbiddenOperationException`, `ResourceNotFoundException` já estão importados; `eq` de `org.mockito.ArgumentMatchers.eq` precisa ser adicionado ao import estático `org.mockito.ArgumentMatchers.*` ou trocar `import static org.mockito.ArgumentMatchers.any;` por `import static org.mockito.ArgumentMatchers.*;`.)

- [x] **Step 2: Rodar os testes e confirmar que falham (método não existe ainda)**

Run: `./mvnw test -Dtest=VehicleServiceTest`
Expected: erro de compilação — `uploadPhoto` não existe em `VehicleService`.

- [x] **Step 3: Implementar `uploadPhoto` em `VehicleService`**

Editar `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`:

```java
// imports adicionais no topo do arquivo
import com.devappmobile.flowfuel.storage.StorageService;
import com.devappmobile.flowfuel.vehicle.dto.PhotoUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
```

```java
// campo + constantes na classe VehicleService
private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
private static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp");

private final StorageService storageService;
```

```java
// novo método público, ao lado dos outros métodos de VehicleService
public PhotoUploadResponse uploadPhoto(User user, Long id, MultipartFile file) {
    Vehicle vehicle = findOwned(user, id);

    if (file == null || file.isEmpty()) {
        throw new BusinessRuleException("Arquivo não informado");
    }

    String contentType = file.getContentType();
    if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
        throw new BusinessRuleException("Tipo de arquivo inválido. Permitido: JPEG, PNG, WEBP");
    }

    if (file.getSize() > MAX_FILE_SIZE) {
        throw new BusinessRuleException("Arquivo excede o tamanho máximo de 5 MB");
    }

    String previousKey = vehicle.getPhoto();
    if (previousKey != null) {
        try {
            storageService.delete(previousKey);
        } catch (Exception ignored) {
        }
    }

    String originalName = file.getOriginalFilename() != null
            ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_")
            : "photo";

    String key = "vehicle_photos/" + id + "_" + originalName;
    storageService.upload(file, key);
    vehicle.setPhoto(key);
    vehicleRepository.save(vehicle);

    return new PhotoUploadResponse("/vehicles/" + id + "/photo");
}
```

`@RequiredArgsConstructor` (já presente na classe) injeta `storageService` automaticamente pelo construtor gerado.

- [x] **Step 4: Rodar os testes e confirmar que passam**

Run: `./mvnw test -Dtest=VehicleServiceTest`
Expected: PASS (todos os testes, incluindo os novos de `uploadPhoto`).

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java
git commit -m "feat(vehicle): add photo upload with type/size validation"
```

---

### Task 3: Endpoint `POST /vehicles/{id}/photo` no controller

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleController.java`

- [x] **Step 1: Adicionar o endpoint**

Editar `VehicleController.java`, adicionando os imports:

```java
import com.devappmobile.flowfuel.vehicle.dto.PhotoUploadResponse;
import org.springframework.web.multipart.MultipartFile;
```

E o método, ao lado dos outros endpoints de `/{id}`:

```java
@PostMapping("/{id}/photo")
public PhotoUploadResponse uploadPhoto(
        @AuthenticationPrincipal User user,
        @PathVariable Long id,
        @RequestParam("file") MultipartFile file) {
    return vehicleService.uploadPhoto(user, id, file);
}
```

- [x] **Step 2: Build para garantir que compila**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [x] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicle/VehicleController.java
git commit -m "feat(vehicle): expose POST /vehicles/{id}/photo endpoint"
```

---

### Task 4: `VehicleResponseDTO.photo` retorna `internalUrl`, não a chave crua

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/dto/VehicleResponseDTO.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java`

- [x] **Step 1: Escrever o teste que falha**

Adicionar em `VehicleServiceTest.java`:

```java
@Test
void getVehicleById_comFotoSalva_retornaInternalUrl() {
    vehicle.setPhoto("vehicle_photos/1_foto.jpg");
    when(vehicleRepository.findById(1L)).thenReturn(Optional.of(vehicle));

    VehicleResponseDTO response = vehicleService.getVehicleById(owner, 1L);

    assertThat(response.getPhoto()).isEqualTo("/vehicles/1/photo");
}

@Test
void getVehicleById_semFoto_retornaPhotoNull() {
    when(vehicleRepository.findById(1L)).thenReturn(Optional.of(vehicle));

    VehicleResponseDTO response = vehicleService.getVehicleById(owner, 1L);

    assertThat(response.getPhoto()).isNull();
}
```

- [x] **Step 2: Rodar e confirmar que falha**

Run: `./mvnw test -Dtest=VehicleServiceTest#getVehicleById_comFotoSalva_retornaInternalUrl`
Expected: FAIL — `response.getPhoto()` retorna a chave crua `vehicle_photos/1_foto.jpg`, não `/vehicles/1/photo`.

- [x] **Step 3: Implementar a conversão em `VehicleResponseDTO.from()`**

Editar `src/main/java/com/devappmobile/flowfuel/vehicle/dto/VehicleResponseDTO.java`:

```java
public static VehicleResponseDTO from(Vehicle v) {
    String photoInternalUrl = v.getPhoto() != null ? ("/vehicles/" + v.getId() + "/photo") : null;

    return VehicleResponseDTO.builder()
            .id(v.getId())
            .type(v.getType())
            .energyType(v.getEnergyType())
            .fuelSubType(v.getFuelSubType())
            .currentKm(v.getCurrentKm())
            .capacity(v.getCapacity())
            .batteryCapacity(v.getBatteryCapacity())
            .brand(v.getBrand())
            .model(v.getModel())
            .manufactureYear(v.getManufactureYear())
            .modelYear(v.getModelYear())
            .color(v.getColor())
            .licensePlate(v.getLicensePlate())
            .photo(photoInternalUrl)
            .isActive(v.getIsActive())
            .createdAt(v.getCreatedAt())
            .updatedAt(v.getUpdatedAt())
            .build();
}
```

- [x] **Step 4: Rodar os testes e confirmar que passam**

Run: `./mvnw test -Dtest=VehicleServiceTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicle/dto/VehicleResponseDTO.java src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java
git commit -m "fix(vehicle): expose photo as internalUrl instead of raw storage key"
```

---

### Task 5: Handler de `MaxUploadSizeExceededException` → `400` consistente

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java`

- [x] **Step 1: Adicionar o handler**

Editar `GlobalExceptionHandler.java`, adicionar o import:

```java
import org.springframework.web.multipart.MaxUploadSizeExceededException;
```

E o handler, ao lado dos outros `@ExceptionHandler`:

```java
@ExceptionHandler(MaxUploadSizeExceededException.class)
public ResponseEntity<ProblemDetail> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex,
        HttpServletRequest req) {
    String detail = "Arquivo excede o tamanho máximo permitido no upload";
    logClientError(ErrorCode.BUSINESS_RULE_VIOLATED, req, detail);
    return build(ErrorCode.BUSINESS_RULE_VIOLATED, detail, req.getRequestURI());
}
```

- [x] **Step 2: Build para garantir que compila**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [x] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java
git commit -m "fix(upload): return 400 instead of 500 when file exceeds servlet multipart limit"
```

---

### Task 6: Testes de integração do endpoint

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleControllerIntegrationTest.java`

- [x] **Step 1: Escrever os testes**

Adicionar ao final da classe (usa o helper `obterToken` e `criarVeiculo` já existentes no arquivo; adicionar import `org.springframework.mock.web.MockMultipartFile` e `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;` já coberto pelo wildcard `.*` existente):

```java
@Test
void uploadPhoto_imagemValida_retorna200ComInternalUrl() throws Exception {
    String token = obterToken("foto-ok@test.com");
    long vehicleId = criarVeiculo(token);

    MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);

    mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
            .file(file)
            .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.internalUrl").value("/vehicles/" + vehicleId + "/photo"));
}

@Test
void uploadPhoto_tipoInvalido_retorna400() throws Exception {
    String token = obterToken("foto-tipo-invalido@test.com");
    long vehicleId = criarVeiculo(token);

    MockMultipartFile file = new MockMultipartFile("file", "foto.gif", "image/gif", new byte[100]);

    mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
            .file(file)
            .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
}

@Test
void uploadPhoto_arquivoAcimaDoLimiteDoServlet_retorna400() throws Exception {
    String token = obterToken("foto-grande@test.com");
    long vehicleId = criarVeiculo(token);

    byte[] arquivoGrande = new byte[6 * 1024 * 1024]; // acima de spring.servlet.multipart.max-file-size (5MB)
    MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", arquivoGrande);

    mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
            .file(file)
            .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
}

@Test
void uploadPhoto_donoDiferente_retorna403() throws Exception {
    String tokenA = obterToken("foto-userA@test.com");
    String tokenB = obterToken("foto-userB@test.com");
    long vehicleId = criarVeiculo(tokenA);

    MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);

    mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
            .file(file)
            .header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isForbidden());
}

@Test
void uploadPhoto_veiculoInexistente_retorna404() throws Exception {
    String token = obterToken("foto-404@test.com");

    MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);

    mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", 999999L)
            .file(file)
            .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
}

@Test
void getVehicleById_aposUploadDeFoto_retornaPhotoComoInternalUrl() throws Exception {
    String token = obterToken("foto-get@test.com");
    long vehicleId = criarVeiculo(token);

    MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);
    mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
            .file(file)
            .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());

    mockMvc.perform(get("/api/v1/vehicles/{id}", vehicleId)
            .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.photo").value("/vehicles/" + vehicleId + "/photo"));
}
```

- [x] **Step 2: Rodar os testes e confirmar que passam**

Run: `./mvnw test -Dtest=VehicleControllerIntegrationTest`
Expected: PASS (todos os testes da classe, incluindo os novos)

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/vehicle/VehicleControllerIntegrationTest.java
git commit -m "test(vehicle): add integration tests for photo upload endpoint"
```

---

### Task 7: Rodar a suíte completa

**Files:** nenhum (apenas verificação)

- [x] **Step 1: Rodar todos os testes**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures.

- [x] **Step 2: Se algo quebrar, investigar e corrigir antes de prosseguir**

Nenhum step de código aqui — só checkpoint de qualidade antes de mexer na documentação.

---

### Task 8: Atualizar `openapi.yaml`

**Files:**
- Modify: `docs/spec/openapi.yaml`

- [x] **Step 1: Adicionar o novo path**

Adicionar ao `docs/spec/openapi.yaml`, na seção de paths de veículos (perto de `/api/v1/vehicles/{id}`), um novo path:

```yaml
/api/v1/vehicles/{id}/photo:
  post:
    tags: [Vehicles]
    summary: Upload de foto do veículo
    parameters:
      - name: id
        in: path
        required: true
        schema:
          type: integer
          format: int64
    requestBody:
      required: true
      content:
        multipart/form-data:
          schema:
            type: object
            required: [file]
            properties:
              file:
                type: string
                format: binary
    responses:
      "200":
        description: Upload concluído
        content:
          application/json:
            schema:
              type: object
              properties:
                internalUrl:
                  type: string
                  example: /vehicles/123/photo
      "400":
        description: Arquivo ausente, tipo inválido, tamanho acima do limite de negócio (5MB) ou acima do limite do servlet
        content:
          application/json:
            examples:
              arquivoAusente:
                value:
                  code: BUSINESS_RULE_VIOLATED
                  detail: "Arquivo não informado"
              tipoInvalido:
                value:
                  code: BUSINESS_RULE_VIOLATED
                  detail: "Tipo de arquivo inválido. Permitido: JPEG, PNG, WEBP"
              tamanhoExcedido:
                value:
                  code: BUSINESS_RULE_VIOLATED
                  detail: "Arquivo excede o tamanho máximo de 5 MB"
      "403":
        $ref: "#/components/responses/ForbiddenNotOwner"
      "404":
        description: Veículo não encontrado
```

- [x] **Step 2: Validar o YAML**

Run: `./mvnw compile` (não valida OpenAPI, só garante que nada quebrou); se houver linter de OpenAPI configurado no projeto, rodá-lo — caso contrário, revisar visualmente a indentação e a referência a `#/components/responses/ForbiddenNotOwner` (já existente no arquivo, conforme confirmado na investigação).

- [x] **Step 3: Commit**

```bash
git add docs/spec/openapi.yaml
git commit -m "docs(openapi): document POST /vehicles/{id}/photo"
```

---

## Fora de escopo (registrado, não implementado)

- `GET /api/v1/vehicles/{id}/photo` (download) e `DELETE /api/v1/vehicles/{id}/photo` (remoção) — não são necessários para o app Android hoje. Ver spec, seção "Endpoints complementares a considerar".
- `VehicleRequestDTO` não recebe campo `photo` — o upload continua sendo uma chamada separada.

## Passo manual pós-deploy (não é código)

Depois que este endpoint estiver em produção, avisar o time do app ou fechar a issue [flow-fuel-api#10](https://github.com/Rochafelip/flow-fuel-api/issues/10) — o client Android já está pronto e só aguardando o endpoint existir.
