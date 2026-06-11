---
id: M8
phase: 2
priority: high
complexity: low-medium
estimate: 1d
status: pending
depends_on: [A2]
---

# M8 — `UserUpdateDTO` dedicado + `@Valid` em `updateProfile`

## Objetivo

Criar um DTO dedicado (`UserUpdateDTO`) para o endpoint `PUT /auth/{userId}/profile` e aplicar `@Valid`, corrigindo a ausência de validação de entrada e o contrato de API enganoso que hoje expõe o campo `password` como aceito.

## Problema Atual

`UserController.updateProfile` (linhas ~119–125) reaproveita `UserRegisterDTO` como corpo de atualização de perfil, **sem `@Valid`**:

```java
@PutMapping("/{userId}/profile")
public UserResponseDTO updateProfile(@PathVariable Long userId,
        @RequestBody UserRegisterDTO userDetails,   // <- sem @Valid
        @AuthenticationPrincipal User authUser) {
```

`UserRegisterDTO` tem `@NotBlank @Email` em `email` e `@NotBlank @Size(min=6)` em `password`, mas como não há `@Valid`, nenhuma dessas validações roda na atualização. Um cliente pode enviar `email: ""` (não-nulo) e o service tenta `user.setEmail("")`, violando a constraint `NOT NULL`/formato esperado apenas na camada de banco.

Além disso, `UserService.updateUserProfile` (linhas ~136–150) ignora silenciosamente o campo `password` do DTO — mas o contrato OpenAPI/Swagger documenta `password` como campo aceito em `PUT /auth/{userId}/profile`, o que é enganoso para consumidores da API.

## Impacto

- Dados inválidos (e-mail vazio/malformado, etc.) podem chegar ao banco sem validação prévia, gerando `DataIntegrityViolationException` (mesma classe de problema do [[A2-data-integrity-handler]]).
- Contrato de API enganoso: `password` aparece como aceito no Swagger/OpenAPI de `PUT /auth/{userId}/profile`, mas é ignorado pelo backend — confunde consumidores (incluindo o app Android).

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/user/UserController.java` (`updateProfile`)
- `src/main/java/com/devappmobile/flowfuel/user/UserService.java` (`updateUserProfile`)
- Novo arquivo: `src/main/java/com/devappmobile/flowfuel/user/UserUpdateDTO.java`
- `src/main/java/com/devappmobile/flowfuel/user/UserRegisterDTO.java` (referência, não deve mais ser usado neste endpoint)
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java`
  - `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java`

## Requisitos Técnicos

- Criar `UserUpdateDTO` como `record`, sem campo `password`, com `@Email` opcional e demais validações pertinentes:
  ```java
  public record UserUpdateDTO(
          @Email String email,
          String name,
          String phone) {}
  ```
- Atualizar `UserController.updateProfile` para receber `@Valid @RequestBody UserUpdateDTO userDetails`.
- Atualizar `UserService.updateUserProfile` para aceitar `UserUpdateDTO` em vez de `UserRegisterDTO`.
- Garantir que campos opcionais (`null`) não sobrescrevam valores existentes indevidamente — preservar a semântica atual de atualização parcial, se houver.
- Atualizar a documentação OpenAPI/Swagger (gerada automaticamente a partir do DTO) para refletir o novo contrato sem `password`.

## Passos de Implementação

1. Ler `UserController.updateProfile` e `UserService.updateUserProfile` para mapear o uso atual de `UserRegisterDTO`.
2. Criar `UserUpdateDTO` (record) com `email`, `name`, `phone` (e quaisquer outros campos de perfil atualizáveis, exceto `password`).
3. Atualizar a assinatura de `updateProfile` no controller para `@Valid @RequestBody UserUpdateDTO`.
4. Atualizar `UserService.updateUserProfile` para usar os getters do novo record (`userDetails.email()`, etc.).
5. Verificar se `findByEmail` + `save` (fluxo de troca de e-mail) ainda segue o mesmo padrão coberto por [[A2-data-integrity-handler]] — não é necessário re-implementar A2 aqui, apenas garantir que o handler de A2 cobre este fluxo.
6. Rodar `mvn -DskipTests=false test` (ou equivalente) focando nos testes de `UserController`/`UserService`.
7. Verificar geração do Swagger/OpenAPI (`/v3/api-docs` ou UI) para confirmar que `password` não aparece mais no schema de `PUT /auth/{userId}/profile`.

## Critérios de Aceitação

- `PUT /auth/{userId}/profile` valida `email` (formato) via `@Valid` e retorna `400` com lista de erros por campo em caso de payload inválido.
- O campo `password` não existe mais no DTO de atualização de perfil nem no schema OpenAPI desse endpoint.
- Atualização de perfil com `email` vazio ou malformado retorna `400 Bad Request` (não chega mais ao banco).
- Testes existentes de atualização de perfil continuam passando (ajustados para o novo DTO).

## Estratégia de Testes

- **Unit tests (`UserServiceTest`):** atualizar testes existentes de `updateUserProfile` para usar `UserUpdateDTO`; adicionar caso de e-mail inválido/vazio.
- **Integration tests (`UserControllerIntegrationTest`):**
  - `PUT /auth/{userId}/profile` com `email` malformado → `400` com erro de validação no campo `email`.
  - `PUT /auth/{userId}/profile` com `password` no corpo da requisição → campo é ignorado/rejeitado pelo deserializer (verificar comportamento do Jackson com campo desconhecido — decidir se deve ser ignorado silenciosamente ou rejeitado, conforme configuração global do projeto).
  - Atualização válida de `name`/`phone`/`email` continua funcionando como antes.
- Verificar regressão na geração do Swagger (teste de contrato, se existir, ou inspeção manual do `/v3/api-docs`).

## Riscos

- Baixo a médio risco — mudança de tipo de DTO em endpoint existente pode quebrar clientes que enviam `password` esperando que seja aceito (hoje já é ignorado, então o impacto real deve ser mínimo).
- Verificar se o app Android (`CONTEXT_FRONTEND_ANDROID.md`) depende do contrato atual de `UserRegisterDTO` neste endpoint — comunicar a mudança de contrato se necessário.

## Dependências

Logicamente posterior a **[[A2-data-integrity-handler]]** — A2 atua como rede de segurança para qualquer dado inválido que ainda passe pela validação de M8 (ex.: e-mail duplicado).

## Estimativa

1 dia.

## Checklist

- [ ] Analisar código atual
- [ ] Implementar solução
- [ ] Adicionar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
