# CRUD completo da foto de veículo — GET e DELETE

> Complementa `docs/superpowers/plans/2026-07-03-upload-foto-veiculo.md` (POST
> já implementado e em produção). Espelha exatamente o padrão já existente
> para foto de perfil de usuário (`UserController`/`UserProfileService`).

## Por quê

O `POST /vehicles/{id}/photo` já existe. Faltam os dois endpoints
complementares para fechar o CRUD da foto: `GET` (servir os bytes da
imagem) e `DELETE` (remover a foto). Já estavam registrados como "fora de
escopo por enquanto" no plano original, sem serem pedidos pelo client
Android até agora.

## Escopo

### Dentro

- `GET /api/v1/vehicles/{id}/photo` — retorna os bytes da imagem
  (`Content-Type` do storage) com `200`, ou `204 No Content` se o veículo
  não tem foto.
- `DELETE /api/v1/vehicles/{id}/photo` — remove a foto do storage e zera
  `Vehicle.photo`; sempre `204`, mesmo se o veículo já não tiver foto
  (no-op silencioso, igual ao comportamento de
  `UserProfileService.removeProfilePicture`).
- Autorização: reusa `VehicleService.findOwned()` (mesmo guard de dono já
  usado em `GET/PUT/DELETE /vehicles/{id}` e no `POST /photo`) — `403` se o
  veículo não pertence ao usuário autenticado, `404` se o veículo não
  existe.
- Atualização de `docs/spec/openapi.yaml` com os dois novos paths.
- Testes unitários (`VehicleServiceTest`) e de integração
  (`VehicleControllerIntegrationTest`), incluindo um teste de round-trip
  upload → get → delete → get (confirma 204 após a remoção).

### Fora

- Qualquer mudança em `VehicleRequestDTO`, `POST /vehicles/{id}/photo` ou no
  fluxo de criação de veículo — já implementados, não mudam.
- Mudança de contrato do client Android — este documento só formaliza os
  dois endpoints que já estavam previstos como possíveis no plano anterior.

## Arquitetura da mudança

**`VehicleController.java`** — dois novos métodos, ao lado dos existentes:

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

**`VehicleService.java`** — dois novos métodos, reusando `findOwned()`:

```java
public ResponseEntity<byte[]> getPhoto(User user, Long id) {
    Vehicle vehicle = findOwned(user, id);
    String key = vehicle.getPhoto();
    if (key == null) return ResponseEntity.noContent().build();

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

Mesma assinatura de `StorageService.StorageObject` já usada em
`UserController.getProfilePicture`. Nenhuma classe nova é necessária.

## OpenAPI

Adicionar em `docs/spec/openapi.yaml`, ao lado do path
`/api/v1/vehicles/{id}/photo` já existente (que hoje só documenta `post`):

- `get`: `200` com `content: application/octet-stream` (ou o content-type
  real do storage), `204` sem corpo, `403` (`$ref:
  '#/components/responses/ForbiddenNotOwner'`), `404`.
- `delete`: `204` sempre, `403` (mesmo `$ref`), `404`.

## Testes

- `VehicleServiceTest`:
  - `getPhoto_semFoto_retorna204`
  - `getPhoto_comFoto_retornaBytesEContentType`
  - `getPhoto_donoDiferente_lancaForbidden`
  - `getPhoto_veiculoInexistente_lancaResourceNotFound`
  - `removePhoto_comFoto_deletaDoStorageEZeraCampo`
  - `removePhoto_semFoto_naoChamaStorageDelete` (no-op)
  - `removePhoto_donoDiferente_lancaForbidden`
  - `removePhoto_veiculoInexistente_lancaResourceNotFound`
- `VehicleControllerIntegrationTest`:
  - `getPhoto_semFotoUpada_retorna204`
  - `getPhoto_aposUpload_retorna200ComBytes`
  - `getPhoto_donoDiferente_retorna403`
  - `deletePhoto_aposUpload_retorna204EGetSubsequenteRetorna204`
  - `deletePhoto_donoDiferente_retorna403`
  - `deletePhoto_veiculoInexistente_retorna404`
