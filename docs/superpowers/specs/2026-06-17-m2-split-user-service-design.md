# Design: M2 — Split `UserService` em `AuthService` + `UserProfileService`

**Data:** 2026-06-17
**Milestone:** M2 (Phase 4)
**Status:** aprovado

## Contexto

`UserService.java` (208 linhas) acumula 5 responsabilidades: autenticação (`register`/`login`/`refresh`/`logout`), gestão de senha (`changePassword`), perfil (`getUserProfile`/`updateUserProfile`/`deleteUser`), upload/remoção de foto de perfil, e — segundo o roadmap original — overloads "legacy" de `uploadProfilePicture`. É a maior violação de SRP do projeto: qualquer feature em `user/` toca o mesmo arquivo, e mocks de `StorageService` poluem testes de login/registro.

**Achados de verificação direta no código atual (não assumidos a partir do roadmap doc):**
- Os overloads legacy `uploadProfilePicture(Long, MultipartFile, boolean)`/`uploadProfilePicture(Long, MultipartFile)` **não existem** em `UserService.java` — só `uploadProfilePictureResponse(Long, MultipartFile)`. O item B2 já foi resolvido em PR anterior.
- A NPE de `User.addVehicle`/`removeVehicle` (B3) **já está corrigida**: `vehicles` é inicializado com `new ArrayList<>()` (`User.java:58`) e `UserTest.java` já cobre os dois métodos. Não há trabalho de B3 pendente para agrupar nesta PR.
- `M1` (`OpaqueTokenGenerator`/`AbstractOpaqueToken`) e `B6` (`AuthorizationHelper`) já estão mergeados — pré-requisitos satisfeitos.
- `grep -rln "UserService" src --include=*.java` retorna apenas `UserController.java` — nenhum outro componente (`DevDataSeeder` etc.) injeta `UserService` diretamente.
- `changePassword` não tem `@Transactional` hoje — o roadmap doc menciona isso como já resolvido por A1, mas não está presente no código; preservar o comportamento atual sem adicionar a anotação (fora de escopo deste split).

## Decisões de Design

### 1. Dois services por responsabilidade, sem superclasse/interface compartilhada

Pacote: `com.devappmobile.flowfuel.user` (mesmo pacote do `UserController`, sem mudança de visibilidade)

- **`AuthService`** — `register`, `login`, `refresh` (delega para `RefreshTokenService`), `logout`, `changePassword`, `deleteUser`, e o helper privado `issueTokenPair`. Dependências: `UserRepository`, `JwtUtil`, `PasswordEncoder`, `RefreshTokenService`, `AccountActivationService`.
- **`UserProfileService`** — `getUserProfile`, `getProfilePictureKey`, `removeProfilePicture`, `updateUserProfile`, `uploadProfilePictureResponse`. Dependências: `UserRepository`, `StorageService`.

**Rationale:** `deleteUser` vai para `AuthService` (não `UserProfileService`) porque excluir conta é semanticamente "encerrar a sessão/identidade do usuário", e mantém paridade com a recomendação do roadmap original. Cada service tem seu próprio `findUserOrThrow(Long)` privado — duplicar 3 linhas é mais simples e direto do que extrair uma classe base só para isso (YAGNI).

**Alternativa descartada:** classe base abstrata `AbstractUserService` com `userRepository` + `findUserOrThrow` compartilhados. Rejeitada porque os dois services não têm mais nada em comum (dependências diferentes, sem polimorfismo necessário) — herança para compartilhar 3 linhas é acoplamento sem benefício.

**Alternativa descartada:** mover `deleteUser` para `UserProfileService`. Rejeitada porque exclusão de conta deveria, futuramente, revogar sessões/tokens — responsabilidade mais próxima de `AuthService` do que de "perfil".

### 2. `UserController` como fachada fina com duas injeções

`UserController` passa a injetar `AuthService` e `UserProfileService` em vez de `UserService`, mantendo `PasswordResetService`, `AccountActivationService` e `StorageService` como já estavam. Nenhum endpoint muda de assinatura, path ou contrato de resposta — apenas o service de destino da delegação.

**Rationale:** zero mudança observável de API; `ensureSelf` (verificação de "só pode operar sobre si mesmo") permanece no controller porque é uma regra transversal aos dois services, não pertence à lógica de negócio de nenhum dos dois.

### 3. Estratégia de migração: "strangler" incremental, sem TDD clássico

Como é um refactor puro (mover código, comportamento idêntico), a disciplina aplicada não é "escrever teste que falha antes do código" — é "manter `UserControllerIntegrationTest` e a suíte de unit tests verde a cada commit". Cada etapa intermediária (copiar método para o novo service, redirecionar controller, remover código morto) é um commit isolado e compilável.

**Alternativa descartada:** big-bang (criar os dois services e apagar `UserService` em um único commit). Rejeitada por aumentar o raio de blast de qualquer erro de merge/regressão em fluxos críticos (login, troca de senha, upload de foto) — o próprio roadmap doc já apontava esse risco como "alto".

## Arquivos Novos

```
src/main/java/com/devappmobile/flowfuel/user/AuthService.java
src/main/java/com/devappmobile/flowfuel/user/UserProfileService.java
src/test/java/com/devappmobile/flowfuel/user/AuthServiceTest.java
src/test/java/com/devappmobile/flowfuel/user/UserProfileServiceTest.java
```

## Arquivos Removidos

```
src/main/java/com/devappmobile/flowfuel/user/UserService.java
src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java
```

## Arquivos Modificados

```
src/main/java/com/devappmobile/flowfuel/user/UserController.java
docs/roadmap/phase-4/M2-split-user-service.md
```

## Testes

### `AuthServiceTest` (Mockito, `@InjectMocks`)
1. `register_comEmailNovo_criaContaPendenteEDisparaAtivacao`
2. `register_senhaEhHasheadaAntesDePersistar`
3. `register_comEmailDuplicado_lancaConflictSemSalvar`
4. `login_comCredenciaisValidas_retornaTokenPair`
5. `login_comEmailInexistente_lancaBadCredentials`
6. `login_comSenhaErrada_lancaBadCredentials`
7. `login_comContaPendente_lancaAccountNotActivated`
8. `changePassword_comSenhaAtualCorreta_atualizaSenhaERevogaRefreshTokens`
9. `changePassword_comSenhaAtualErrada_lancaBadCredentialsSemAlterar`
10. `changePassword_comNovaSenhaIgualAtual_lancaBusinessRule`
11. `deleteUser_existente_deleta`
12. `deleteUser_inexistente_lancaResourceNotFound`

### `UserProfileServiceTest` (Mockito, `@InjectMocks`)
1. `getUserProfile_usuarioExistente_retornaDto`
2. `getUserProfile_usuarioInexistente_lancaResourceNotFound`
3. `getUserProfile_retornaProfilePictureUrl`
4. `upload_comTipoInvalido_lancaBusinessRule`
5. `upload_comArquivoMaiorQue5MB_lancaBusinessRule`
6. `upload_comImagemValida_atualizaPath`
7. `uploadProfilePictureResponse_comImagemValida_retornaUrls`
8. `updateUserProfile_comNameEPhone_atualizaSemTocarEmail`
9. `updateUserProfile_comEmailNovo_verificaDuplicidadeEAtualiza`
10. `updateUserProfile_comEmailDuplicado_lancaConflict`
11. `updateUserProfile_comTodosCamposNulos_naoAlteraNada`
12. `updateUserProfile_usuarioInexistente_lancaResourceNotFound`

### Regressão
- `UserControllerIntegrationTest` (807 linhas, todos os 11 endpoints de `/auth`) deve passar sem alteração de contrato após cada commit de migração.
- `mvn test` completo (não só o pacote `user`) ao final, para capturar dependências cruzadas inesperadas.

## Critérios de Aceitação

- `UserService.java` não existe mais — substituído por `AuthService` e `UserProfileService`, cada um com responsabilidade única.
- `UserController` não contém lógica de negócio — apenas delega e mapeia request/response.
- `UserControllerIntegrationTest` passa integralmente, sem alteração de contrato de API observável.
- Nenhuma referência a `UserService` permanece no código (`grep -rn "UserService" src --include=*.java` vazio).
- Toda a suíte (`mvn test`) passa.

## Riscos e Mitigações

- **Maior refatoração estrutural do roadmap** — mitigado dividindo em 7 commits incrementais e compiláveis (Tasks 1–7 do plano), cada um seguido de execução de testes.
- **Dependência circular entre `AuthService`/`UserProfileService`** — não ocorre nesta divisão: nenhum método de perfil depende de lógica de auth nem vice-versa (verificado nos requisitos técnicos copiados acima).
- **Itens do roadmap doc desatualizados (B2, B3)** — risco de retrabalho/confusão se alguém tentar "remover overloads legacy" ou "corrigir NPE" que já não existem. Mitigado documentando explicitamente em "Contexto" e atualizando o checklist do roadmap doc na Task 7 do plano.
