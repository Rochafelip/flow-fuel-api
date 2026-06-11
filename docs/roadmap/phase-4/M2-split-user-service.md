---
id: M2
phase: 4
priority: low
complexity: high
estimate: 4-6d
status: pending
depends_on: [M1, B6]
---

# M2 — Split de `UserService` em `AuthService` + `UserProfileService`

## Objetivo

Dividir `UserService` (220 linhas, 6 dependências, 5 responsabilidades) em `AuthService` (autenticação/sessão/senha) e `UserProfileService` (perfil/foto), mantendo `UserController` como fachada fina que delega a ambos, e remover os overloads "legacy" de `uploadProfilePicture`.

## Problema Atual

`UserService.java` acumula 5 responsabilidades distintas:

1. **Autenticação:** registro, login, refresh (delegado), logout.
2. **Gestão de senha:** `changePassword`.
3. **Perfil:** `getUserProfile`, `updateUserProfile`, `deleteUser`.
4. **Upload/remoção de foto de perfil:** validação de tipo/tamanho de arquivo, geração de chave S3.
5. **Resquícios de compatibilidade:** `uploadProfilePicture(..., boolean legacy)` — método "backwards-compatible" comentado como mantido só para testes.

Esta é a **maior violação de SRP do projeto**, com alta probabilidade de conflitos de merge, dificuldade de testar isoladamente, e violação do princípio Aberto/Fechado (qualquer mudança em upload de mídia exige recompilar/revalidar toda a classe de autenticação).

## Impacto

- Alta probabilidade de conflitos de merge em `UserService.java` (qualquer feature relacionada a `user/` toca o mesmo arquivo de 220 linhas).
- Dificuldade de testar autenticação isoladamente de upload de mídia (mocks de `StorageService` poluem testes de login/registro e vice-versa).
- Overloads legacy de `uploadProfilePicture` (`uploadProfilePicture(Long, MultipartFile, boolean)` / `uploadProfilePicture(Long, MultipartFile)`) são código morto/quase-morto mantido apenas por dependência de testes antigos.

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/user/UserService.java` (a ser dividido)
- Novo: `src/main/java/com/devappmobile/flowfuel/user/AuthService.java`
- Novo: `src/main/java/com/devappmobile/flowfuel/user/UserProfileService.java`
- `src/main/java/com/devappmobile/flowfuel/user/UserController.java` (vira fachada fina, delega para `AuthService`/`UserProfileService`)
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java` (a ser dividido em `AuthServiceTest`/`UserProfileServiceTest`)
  - `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java` (647 linhas — ajustar conforme necessário, mantendo cobertura ponta-a-ponta)

## Requisitos Técnicos

- **`AuthService`:** `register`, `login`, `refresh` (delegação), `logout`, `changePassword` (já com `@Transactional` de [[A1-transactional-boundaries]]), `deleteUser` (avaliar se exclusão de conta é "auth" ou "perfil" — recomenda-se `AuthService` por envolver revogação de sessões/tokens).
- **`UserProfileService`:** `getUserProfile`, `updateUserProfile` (já usando `UserUpdateDTO` de [[M8-user-update-dto-validation]]), `uploadProfilePictureResponse` (método canônico, sem overloads legacy), remoção de foto.
- `UserController` passa a injetar `AuthService` e `UserProfileService`, delegando cada endpoint ao service correspondente — sem lógica de negócio no controller.
- **Remover** `uploadProfilePicture(Long, MultipartFile, boolean)` e `uploadProfilePicture(Long, MultipartFile)` (overloads legacy) — atualizar todos os testes que os referenciam para chamar `uploadProfilePictureResponse` diretamente (ver [[B2-remove-dead-code]], que depende desta remoção).
- Aproveitar que `User.java` está sendo tocado para corrigir a NPE de `addVehicle`/`removeVehicle` ([[B3-fix-user-add-vehicle-npe]]) no mesmo PR, por proximidade de arquivo.
- Reutilizar `AuthorizationHelper` ([[B6-authorization-helper]]) e `OpaqueTokenGenerator`/`AbstractOpaqueToken` ([[M1-opaque-token-generator]]) já extraídos — não duplicar essa lógica nos novos services.

## Passos de Implementação

1. Confirmar que [[M1-opaque-token-generator]] e [[B6-authorization-helper]] estão concluídos (pré-requisitos).
2. Mapear todos os métodos públicos de `UserService` e classificá-los entre `AuthService`/`UserProfileService`/"remover" (overloads legacy).
3. Criar `AuthService` movendo: `register`, `login`, `refresh` (delegação para `RefreshTokenService`), `logout`, `changePassword`, `deleteUser`.
4. Criar `UserProfileService` movendo: `getUserProfile`, `updateUserProfile`, `uploadProfilePictureResponse`, remoção de foto de perfil.
5. Remover os overloads legacy `uploadProfilePicture(Long, MultipartFile, boolean)` / `uploadProfilePicture(Long, MultipartFile)`.
6. Atualizar `UserController` para injetar e delegar para os dois novos services (fachada fina).
7. Dividir `UserServiceTest` em `AuthServiceTest` e `UserProfileServiceTest`, atualizando todos os testes que referenciavam os overloads legacy para usar `uploadProfilePictureResponse`.
8. Corrigir [[B3-fix-user-add-vehicle-npe]] em `User.java` no mesmo PR (proximidade de arquivo).
9. Rodar `UserControllerIntegrationTest` (647 linhas) integralmente — é o teste de regressão mais importante para esta task, pois cobre o fluxo completo de auth/ativação/reset/perfil ponta-a-ponta.
10. Revisar injeção de dependências em `UserController` (6 dependências de `UserService` agora distribuídas entre 2 services — confirmar que não há dependência circular entre `AuthService`/`UserProfileService`).

## Critérios de Aceitação

- `UserService.java` não existe mais (ou existe apenas como classe vazia/removida) — substituído por `AuthService` e `UserProfileService`, cada um com responsabilidade única e coesa.
- `UserController` não contém lógica de negócio — apenas delega para os 2 services e mapeia request/response.
- Overloads legacy `uploadProfilePicture(Long, MultipartFile, boolean)` / `uploadProfilePicture(Long, MultipartFile)` não existem mais.
- `UserControllerIntegrationTest` (todos os 11 endpoints de `/auth`) passa integralmente, sem alteração de contrato de API observável.
- [[B3-fix-user-add-vehicle-npe]] está corrigido como parte deste PR.

## Estratégia de Testes

- **Estratégia "strangler":** manter `UserControllerIntegrationTest` passando em todo momento durante a refatoração — é o teste de regressão de maior valor (cobre o fluxo ponta-a-ponta de todos os 11 endpoints de `/auth`).
- **Unit tests:** dividir `UserServiceTest` em `AuthServiceTest` (registro, login, refresh, logout, changePassword, deleteUser) e `UserProfileServiceTest` (perfil, upload/remoção de foto), preservando os casos de teste existentes (apenas reorganizando por classe).
- **Teste específico para overloads removidos:** confirmar que nenhum teste restante referencia `uploadProfilePicture(Long, MultipartFile, boolean)`/`uploadProfilePicture(Long, MultipartFile)` — todos devem usar `uploadProfilePictureResponse`.
- Rodar a suíte completa do projeto (`mvn test`) ao final, não apenas o pacote `user`, para capturar qualquer dependência cruzada inesperada (ex.: `DevDataSeeder` usando `UserService`).

## Riscos

- **Alto risco** — maior refatoração estrutural do roadmap, maior risco de conflito de merge e de regressão silenciosa em fluxos críticos (login, registro, troca de senha, upload de foto).
- Recomenda-se **feature branch de vida curta** com testes verdes antes do merge (conforme nota de entrega da Fase 4).
- Risco de dependência circular entre `AuthService` e `UserProfileService` se algum método de perfil precisar de lógica de auth (ou vice-versa) — mapear isso ANTES de começar a mover código.
- Risco de `DevDataSeeder` (`@Profile("dev")`) ou outros componentes injetarem `UserService` diretamente — buscar (`grep`) todas as injeções de `UserService` no projeto antes de remover a classe.

## Dependências

**Requer [[M1-opaque-token-generator]] e [[B6-authorization-helper]] concluídos** (Fase 3). Custo de execução de M2 diminui significativamente após M1 (tokens já extraídos) e B6 (helper de autorização já extraído) — fazer M2 antes geraria retrabalho. Também é o momento natural para remover os overloads `uploadProfilePicture` (ver [[B2-remove-dead-code]]).

## Estimativa

4–6 dias (inclui reescrita/realocação de testes unitários e de integração existentes).

## Checklist

- [ ] Confirmar conclusão de M1 e B6
- [ ] Analisar código atual
- [ ] Implementar solução (split em AuthService + UserProfileService)
- [ ] Remover overloads legacy de uploadProfilePicture
- [ ] Corrigir B3 (NPE addVehicle/removeVehicle) no mesmo PR
- [ ] Adicionar/realocar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão (UserControllerIntegrationTest completo)
- [ ] Abrir PR
