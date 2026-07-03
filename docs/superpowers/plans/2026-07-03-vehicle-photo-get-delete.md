# CRUD completo da foto de veículo (GET + DELETE) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar `GET /api/v1/vehicles/{id}/photo` (servir bytes da imagem) e `DELETE /api/v1/vehicles/{id}/photo` (remover foto), completando o CRUD já iniciado com `POST` (implementado e em produção).

**Architecture:** Espelha exatamente `UserController`/`UserProfileService` (foto de perfil): `GET` retorna `204` se `Vehicle.photo == null`, senão baixa do `StorageService` (implementação real: `PostgresStorageService`) e devolve bytes + `Content-Type`; `DELETE` apaga do storage e zera o campo, sempre `204` (no-op silencioso se já não houver foto). Autorização reusa `VehicleService.findOwned()` já existente (dono → `403`, inexistente → `404`).

**Tech Stack:** Java 21, Spring Boot, Maven (`./mvnw`), JUnit 5 + Mockito (unitários), MockMvc (integração).

---

## File Structure

- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleController.java` — novos endpoints `GET`/`DELETE` `/{id}/photo`.
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java` — métodos `getPhoto` e `removePhoto`.
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java` — testes unitários.
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleControllerIntegrationTest.java` — testes de integração.
- Modify: `docs/spec/openapi.yaml` — documenta os dois novos métodos no path já existente `/api/v1/vehicles/{id}/photo`.

---

### Task 1: `VehicleService.getPhoto` e `VehicleService.removePhoto`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`
- Test: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java`

- [ ] **Step 1: Escrever os testes que falham**

Adicionar ao final de `VehicleServiceTest.java`, antes do último `}` da classe:

```java
    // --- getPhoto ---

    @Test
    void getPhoto_semFoto_retorna204() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        org.springframework.http.ResponseEntity<byte[]> response = vehicleService.getPhoto(owner, 10L);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.NO_CONTENT);
        verify(storageService, never()).download(any());
    }

    @Test
    void getPhoto_comFoto_retornaBytesEContentType() {
        vehicle.setPhoto("vehicle_photos/10_foto.jpg");
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        byte[] bytes = new byte[]{1, 2, 3};
        when(storageService.download("vehicle_photos/10_foto.jpg"))
                .thenReturn(new StorageService.StorageObject(bytes, "image/jpeg"));

        org.springframework.http.ResponseEntity<byte[]> response = vehicleService.getPhoto(owner, 10L);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(bytes);
        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("image/jpeg");
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
```

- [ ] **Step 2: Rodar os testes e confirmar que falham**

Run: `./mvnw test -Dtest=VehicleServiceTest`
Expected: erro de compilação — `getPhoto`/`removePhoto` não existem em `VehicleService`.

- [ ] **Step 3: Implementar `getPhoto` e `removePhoto` em `VehicleService`**

Editar `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`. Adicionar o import:

```java
import org.springframework.http.ResponseEntity;
```

Adicionar os dois métodos, logo após `uploadPhoto`:

```java
    public ResponseEntity<byte[]> getPhoto(User user, Long id) {
        Vehicle vehicle = findOwned(user, id);
        String key = vehicle.getPhoto();
        if (key == null) {
            return ResponseEntity.noContent().build();
        }

        StorageService.StorageObject obj = storageService.download(key);
        return ResponseEntity.ok()
                .header("Content-Type", obj.contentType())
                .body(obj.data());
    }

    public void removePhoto(User user, Long id) {
        Vehicle vehicle = findOwned(user, id);
        String key = vehicle.getPhoto();
        if (key != null) {
            storageService.delete(key);
            vehicle.setPhoto(null);
            vehicleRepository.save(vehicle);
        }
    }
```

- [ ] **Step 4: Rodar os testes e confirmar que passam**

Run: `./mvnw test -Dtest=VehicleServiceTest`
Expected: PASS (todos os testes, incluindo os novos de `getPhoto`/`removePhoto`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java
git commit -m "feat(vehicle): add getPhoto and removePhoto to VehicleService"
```

---

### Task 2: Endpoints `GET`/`DELETE /vehicles/{id}/photo` no controller

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleController.java`

- [ ] **Step 1: Adicionar os endpoints**

Editar `VehicleController.java`, adicionando o import:

```java
import org.springframework.http.ResponseEntity;
```

E os dois métodos, logo após `uploadPhoto`:

```java
    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> getPhoto(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        return vehicleService.getPhoto(user, id);
    }

    @DeleteMapping("/{id}/photo")
    public ResponseEntity<Void> deletePhoto(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        vehicleService.removePhoto(user, id);
        return ResponseEntity.noContent().build();
    }
```

- [ ] **Step 2: Build para garantir que compila**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicle/VehicleController.java
git commit -m "feat(vehicle): expose GET and DELETE /vehicles/{id}/photo endpoints"
```

---

### Task 3: Testes de integração

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleControllerIntegrationTest.java`

- [ ] **Step 1: Escrever os testes**

Adicionar ao final da classe, antes do último `}` (usa os helpers `obterToken`, `criarVeiculo` e `imagemJpegValida` já existentes no arquivo):

```java
    @Test
    void getPhoto_semFotoUpada_retorna204() throws Exception {
        String token = obterToken("foto-get204@test.com");
        long vehicleId = criarVeiculo(token);

        mockMvc.perform(get("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void getPhoto_aposUpload_retorna200ComBytes() throws Exception {
        String token = obterToken("foto-get200@test.com");
        long vehicleId = criarVeiculo(token);

        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", imagemJpegValida());
        mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
    }

    @Test
    void getPhoto_donoDiferente_retorna403() throws Exception {
        String tokenA = obterToken("foto-get-userA@test.com");
        String tokenB = obterToken("foto-get-userB@test.com");
        long vehicleId = criarVeiculo(tokenA);

        mockMvc.perform(get("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPhoto_veiculoInexistente_retorna404() throws Exception {
        String token = obterToken("foto-get404@test.com");

        mockMvc.perform(get("/api/v1/vehicles/{id}/photo", 999999L)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePhoto_aposUpload_retorna204EGetSubsequenteRetorna204() throws Exception {
        String token = obterToken("foto-delete@test.com");
        long vehicleId = criarVeiculo(token);

        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", imagemJpegValida());
        mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletePhoto_semFotoExistente_retorna204SemErro() throws Exception {
        String token = obterToken("foto-delete-noop@test.com");
        long vehicleId = criarVeiculo(token);

        mockMvc.perform(delete("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletePhoto_donoDiferente_retorna403() throws Exception {
        String tokenA = obterToken("foto-delete-userA@test.com");
        String tokenB = obterToken("foto-delete-userB@test.com");
        long vehicleId = criarVeiculo(tokenA);

        mockMvc.perform(delete("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletePhoto_veiculoInexistente_retorna404() throws Exception {
        String token = obterToken("foto-delete404@test.com");

        mockMvc.perform(delete("/api/v1/vehicles/{id}/photo", 999999L)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: Rodar os testes e confirmar que passam**

Run: `./mvnw test -Dtest=VehicleControllerIntegrationTest`
Expected: PASS (todos os testes da classe, incluindo os novos).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/vehicle/VehicleControllerIntegrationTest.java
git commit -m "test(vehicle): add integration tests for GET/DELETE photo endpoints"
```

---

### Task 4: Rodar a suíte completa

**Files:** nenhum (apenas verificação)

- [ ] **Step 1: Rodar todos os testes**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 2: Se algo quebrar, investigar e corrigir antes de prosseguir**

Nenhum step de código aqui — só checkpoint de qualidade antes de mexer na documentação.

---

### Task 5: Atualizar `openapi.yaml`

**Files:**
- Modify: `docs/spec/openapi.yaml`

- [ ] **Step 1: Adicionar `get` e `delete` ao path existente**

O path `/api/v1/vehicles/{id}/photo` já existe em `docs/spec/openapi.yaml` (linha ~704) com o método `post`. Adicionar `get` e `delete` como irmãos de `post`, dentro do mesmo bloco do path (ficam ao lado, na mesma indentação de `post:`):

```yaml
    get:
      tags: [Vehicles]
      summary: Baixa a foto do veículo
      description: >-
        Retorna os bytes da imagem armazenada (Content-Type do storage), ou
        204 se o veículo não tiver foto cadastrada.
      parameters:
        - $ref: "#/components/parameters/id"
      responses:
        "200":
          description: Bytes da imagem
          content:
            image/jpeg:
              schema:
                type: string
                format: binary
        "204":
          description: Veículo não possui foto
        "403":
          $ref: "#/components/responses/ForbiddenNotOwner"
        "404":
          $ref: "#/components/responses/NotFound"
    delete:
      tags: [Vehicles]
      summary: Remove a foto do veículo
      description: >-
        Remove a foto do storage e zera Vehicle.photo. Sempre retorna 204,
        mesmo se o veículo já não tiver foto (no-op silencioso).
      parameters:
        - $ref: "#/components/parameters/id"
      responses:
        "204":
          description: Foto removida (ou já não existia)
        "403":
          $ref: "#/components/responses/ForbiddenNotOwner"
        "404":
          $ref: "#/components/responses/NotFound"
```

- [ ] **Step 2: Validar visualmente**

Run: `./mvnw compile` (não valida OpenAPI, só garante que nada quebrou); revisar visualmente a indentação do YAML (deve alinhar com `post:` no mesmo path) e confirmar que `#/components/parameters/id` e `#/components/responses/ForbiddenNotOwner`/`NotFound` já existem no arquivo (confirmado: usados pelo `post` do mesmo path).

- [ ] **Step 3: Commit**

```bash
git add docs/spec/openapi.yaml
git commit -m "docs(openapi): document GET/DELETE /vehicles/{id}/photo"
```

---

## Fora de escopo (registrado, não implementado)

- Mudanças em `VehicleRequestDTO`, `POST /vehicles/{id}/photo` ou no fluxo de criação — não mudam.
- Mudança de contrato do client Android.
