# A1 — Transactional boundaries em fluxos multi-entidade (design)

Referência: `docs/roadmap/phase-1/A1-transactional-boundaries.md`

## Objetivo

Tornar atômicos três fluxos de serviço que hoje fazem múltiplas escritas de
repositório em chamadas separadas (cada `save`/`saveAll` commitando
independentemente):

- `RefuelService.createRefuel`
- `VehicleService.setActiveVehicle`
- `UserService.changePassword`

## Abordagem

### Mudanças de produção

Adicionar `@org.springframework.transaction.annotation.Transactional` aos
três métodos acima, sem alterar lógica de negócio. `RefreshTokenService.*`
já é `@Transactional` com propagação `REQUIRED` (default), então
`changePassword` participa da mesma transação ao chamar
`revokeAllForUser`.

### Estratégia de testes para rollback

Abordagens consideradas:

1. **`@MockitoSpyBean` forçando falha na "segunda escrita" (escolhida)** —
   Spring Boot 3.5 permite `@MockitoSpyBean` envolvendo o bean real do
   repositório. Stubamos apenas o segundo método de escrita para lançar
   `RuntimeException`, deixando todas as outras chamadas (incluindo a
   primeira escrita) baterem no H2 real. Depois da exception, reconsultamos
   via repositórios reais para confirmar que nada foi persistido.
2. Violação real de constraint (anular coluna NOT NULL) — descartada: timing
   de flush do Hibernate é difícil de controlar e de direcionar para a
   segunda escrita especificamente.
3. Testes unitários Mockito apenas verificando propagação da exception —
   descartada como única estratégia: não prova rollback real em DB (mas
   testes unitários existentes continuam cobrindo os caminhos felizes).

### Novas classes de teste (`@SpringBootTest`, H2 real)

- `RefuelServiceTransactionalTest`
  - Seed: usuário + veículo (via repositórios).
  - Spy `VehicleRepository.save()` lança `RuntimeException`.
  - Chama `createRefuel` → espera exception.
  - Assert: `refuelRepository.findAll()` vazio e `vehicle.currentKm`
    inalterado (refuel não persiste se o save do veículo falhar).

- `VehicleServiceTransactionalTest`
  - Seed: usuário com 2 veículos, um ativo.
  - Spy `VehicleRepository.saveAll()` lança `RuntimeException`.
  - Chama `setActiveVehicle` para o outro veículo → espera exception.
  - Assert: flags `active` inalteradas em ambos os veículos no DB.

- `UserServiceTransactionalTest`
  - Seed: usuário com senha conhecida + refresh token ativo.
  - Spy `RefreshTokenRepository.revokeAllActiveByUserId()` lança
    `RuntimeException`.
  - Chama `changePassword` → espera exception.
  - Assert: hash de senha do usuário inalterado no DB e refresh token
    permanece não revogado (`revokedAt == null`).

### Testes existentes

`RefuelServiceTest`, `VehicleServiceTest`, `UserServiceTest` (Mockito,
`@ExtendWith(MockitoExtension.class)`) não são afetados por
`@Transactional` (não há proxy real em testes unitários) e devem continuar
passando sem alteração.

## Arquivos afetados

- `src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java`
- `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`
- `src/main/java/com/devappmobile/flowfuel/user/UserService.java`
- Novos:
  - `src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTransactionalTest.java`
  - `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTransactionalTest.java`
  - `src/test/java/com/devappmobile/flowfuel/user/UserServiceTransactionalTest.java`

## Riscos

- Baixo risco — mudança aditiva (anotação) + novos testes isolados.
- `@MockitoSpyBean` cria um contexto Spring adicional por configuração de
  override; aceitável dado o número pequeno de novas classes de teste.
