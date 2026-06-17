---
id: M7
phase: 3
priority: medium
complexity: medium
estimate: 1-2d
status: done
depends_on: []
---

# M7 — Remover `cascade = ALL, orphanRemoval = true` de `Vehicle.refuels` e `User.vehicles`

## Objetivo

Remover `cascade = CascadeType.ALL, orphanRemoval = true` das coleções `Vehicle.refuels` e `User.vehicles`, já que a integridade referencial é garantida pelo `ON DELETE CASCADE` do PostgreSQL/Flyway, evitando deletes linha-a-linha em coleções grandes.

## Problema Atual

`@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)` faz o Hibernate carregar a coleção inteira e emitir um `DELETE` por linha ao remover o pai (em vez de um `DELETE ... WHERE vehicle_id = ?` em lote), mesmo havendo `ON DELETE CASCADE` no banco (que torna o cascade JPA redundante).

Adicionalmente, `UserService.deleteUser` usa `userRepository.deleteById(userId)` (sem carregar a entidade) — nesse caminho o cascade JPA **não é acionado** (Hibernate precisa da entidade gerenciada para cascatear em memória); quem efetivamente limpa `vehicles`/`refuels`/tokens é o `ON DELETE CASCADE` do banco. Ou seja, `cascade = ALL` em `User.vehicles` já é, na prática, **morto** para o fluxo de exclusão de usuário, mas continua ativo (e custoso) para fluxos que carregam `Vehicle` e o removem via `vehicleRepository.delete(vehicle)`.

## Impacto

- Para um veículo com milhares de reabastecimentos, deletar o veículo pode gerar **milhares de statements** `DELETE` individuais em vez de um único `DELETE` em lote via constraint do banco.
- Modelo de entidade mais complexo do que o necessário, com comportamento de cascade redundante e parcialmente "morto" (inconsistência entre o que o código sugere e o que de fato acontece em runtime, dependendo do caminho de exclusão).

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/vehicle/Vehicle.java` (campo `refuels`, linha ~81)
- `src/main/java/com/devappmobile/flowfuel/user/User.java` (campo `vehicles`, linha ~56)
- Migrations Flyway (`src/main/resources/db/migration/`) — **apenas para confirmação**, não deve ser necessário criar nova migration, já que `ON DELETE CASCADE` já existe no schema.
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java`
  - `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleControllerIntegrationTest.java`
  - `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java`
  - `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java`
  - `src/test/java/com/devappmobile/flowfuel/refuel/*` (fluxos de exclusão de veículo que afetam refuels)

## Requisitos Técnicos

- Remover `cascade = CascadeType.ALL, orphanRemoval = true` de:
  - `Vehicle.refuels` (`@OneToMany(mappedBy = "vehicle", ...)`)
  - `User.vehicles` (`@OneToMany(mappedBy = "user", ...)`)
- Confirmar, via inspeção das migrations Flyway (V1-V7), que as constraints `ON DELETE CASCADE` existem para `refuels.vehicle_id` e `vehicles.user_id`.
- **Antes da mudança**, mapear todos os fluxos que dependem do cascade JPA em memória (não apenas do `ON DELETE CASCADE` do banco):
  - Algum fluxo chama `vehicleRepository.delete(vehicle)` esperando que `refuels` associados sejam removidos em cascata pelo Hibernate?
  - Algum fluxo chama `userRepository.delete(user)` (não `deleteById`) esperando cascade de `vehicles`?
- Se algum fluxo depender do cascade JPA, ajustar para depender apenas do `ON DELETE CASCADE` do banco (a remoção da entidade pai já dispara a remoção em cascata no banco, independente do mapeamento JPA).

## Passos de Implementação

1. Buscar (`grep`) por todos os usos de `vehicleRepository.delete(...)`, `vehicleRepository.deleteById(...)`, `userRepository.delete(...)`, `userRepository.deleteById(...)` no código de produção.
2. Para cada uso encontrado, verificar se o teste correspondente assume que `refuels`/`vehicles` associados são removidos — e se esse teste passaria mesmo sem o cascade JPA (i.e., dependendo apenas do `ON DELETE CASCADE` do banco, que H2/Postgres em testes de integração também respeitam).
3. Confirmar nas migrations Flyway que `refuels.vehicle_id` e `vehicles.user_id` têm `ON DELETE CASCADE`.
4. Remover `cascade = CascadeType.ALL, orphanRemoval = true` de `Vehicle.refuels` e `User.vehicles` (manter `mappedBy` e demais atributos do `@OneToMany`).
5. Rodar a suíte completa de testes de `vehicle`, `user`, `refuel` — com foco especial em testes de exclusão de veículo/usuário com dados associados.
6. Validar manualmente (ou via teste de integração com H2/Postgres) que excluir um veículo com reabastecimentos associados remove os reabastecimentos corretamente (via `ON DELETE CASCADE`, não mais via Hibernate).

## Critérios de Aceitação

- `Vehicle.refuels` e `User.vehicles` não possuem mais `cascade = CascadeType.ALL, orphanRemoval = true`.
- Excluir um veículo remove seus reabastecimentos e eventos associados (validado via `ON DELETE CASCADE`).
- Excluir um usuário remove seus veículos (e, transitivamente, reabastecimentos/eventos) associados.
- Nenhum teste de regressão de exclusão falha.
- (Se possível medir) número de statements SQL emitidos ao excluir um veículo com múltiplos reabastecimentos é reduzido (de N+1 deletes para 1 delete em lote no banco — observável via `ON DELETE CASCADE`, não via Hibernate).

## Estratégia de Testes

- **Cobertura de teste de regressão obrigatória ANTES da mudança** (conforme apontado no roadmap): garantir que os fluxos de exclusão de veículo/usuário tenham testes que cubram o cenário "entidade pai com filhos associados é excluída e os filhos desaparecem".
- **Testes de integração (`@SpringBootTest` + H2 ou Testcontainers Postgres):**
  - Criar veículo com N reabastecimentos → excluir veículo → verificar que `refuelRepository.findByVehicleId(id)` retorna vazio.
  - Criar usuário com veículos (e reabastecimentos) → excluir usuário → verificar que `vehicleRepository.findByUserId(id)` e reabastecimentos associados retornam vazio.
- Repetir os mesmos testes **antes e depois** da remoção do cascade para confirmar paridade de comportamento.
- Atenção: H2 (usado em testes) precisa respeitar `ON DELETE CASCADE` da mesma forma que PostgreSQL — confirmar que o schema de teste (gerado via `ddl-auto=create-drop` em `dev`/`test`) replica fielmente as constraints de cascade definidas nas migrations Flyway de prod.

## Riscos

- **Risco de regressão silenciosa** se algum fluxo depender do cascade JPA em memória sem teste que cubra esse caso — por isso a cobertura de teste de regressão é pré-requisito explícito desta task, não opcional.
- Risco de divergência entre o schema de teste (`ddl-auto=create-drop`, gerado a partir das entidades) e o schema de produção (Flyway) quanto às constraints `ON DELETE CASCADE` — validar que ambos estão alinhados antes de confiar nos testes de H2 como prova suficiente.

## Dependências

Nenhuma dependência de outras tasks do roadmap. **Requer cobertura de teste de regressão para os fluxos de exclusão de veículo/usuário antes da mudança** (pré-requisito interno desta task).

## Estimativa

1–2 dias (incluindo testes de regressão).

## Checklist

- [x] Analisar código atual
- [x] Mapear fluxos que dependem do cascade JPA em memória
- [x] Adicionar/confirmar testes de regressão de exclusão (antes da mudança)
- [x] Implementar solução
- [x] Adicionar testes
- [x] Atualizar documentação
- [x] Executar testes de regressão
- [ ] Abrir PR

## Notas de Implementação

- Achado adicional não previsto no doc original: os testes (`src/test/resources/application.properties`) na verdade executam as migrations Flyway (Flyway habilitado por omissão nesse perfil), mas o `ddl-auto=create-drop` do Hibernate recria o schema por cima a partir das entidades — então o `ON DELETE CASCADE` das migrations não chega a valer no H2 de teste a menos que o mapeamento JPA também o declare.
- Solução adotada: anotação `@OnDelete(action = OnDeleteAction.CASCADE)` do Hibernate (`org.hibernate.annotations.OnDelete`) no lado `@ManyToOne` de `Vehicle.user` e `Refuel.vehicle`. Isso faz o Hibernate gerar `ON DELETE CASCADE` também no DDL gerado para H2, mantendo paridade com o schema de produção (Flyway) sem exigir cascade JPA em memória.
- `vehicleRepository.deleteById(id)` (`VehicleService.deleteVehicle`) e `userRepository.deleteById(userId)` (`UserService.deleteUser`) já não carregavam a entidade gerenciada — o cascade JPA nunca era acionado nesses fluxos; a remoção dependia apenas do `ON DELETE CASCADE` do banco, confirmando a análise do documento original.
- Testes de regressão adicionados: `VehicleControllerIntegrationTest#deleteVehicle_comReabastecimentosAssociados_removeReabastecimentos` e `UserControllerIntegrationTest#deleteUser_comVeiculosEReabastecimentosAssociados_removeTudo`. Ambos foram verificados em RED (sem `@OnDelete`, falhavam com 409 por violação de FK) e depois em GREEN (com `@OnDelete`).
- Suíte completa (`./mvnw test`) passa sem regressões.
