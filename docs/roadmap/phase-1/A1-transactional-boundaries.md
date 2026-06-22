---
id: A1
phase: 1
priority: critical
complexity: low
estimate: 0.5-1d
status: done
depends_on: []
---

# A1 — Adicionar `@Transactional` em fluxos multi-entidade

## Objetivo

Garantir atomicidade nos fluxos de serviço que persistem ou atualizam mais de um agregado/tabela, eliminando a possibilidade de "estado parcialmente salvo" em caso de falha entre escritas.

## Problema Atual

Os métodos abaixo executam múltiplas operações de persistência em chamadas de repositório separadas, sem um boundary transacional explícito no nível do `@Service`:

- `RefuelService.createRefuel` (linhas ~52–67): salva `Refuel` e depois atualiza/salva `Vehicle` (currentKm) em duas chamadas separadas.
- `VehicleService.setActiveVehicle` (linhas ~65–71): itera todos os veículos do usuário e faz `saveAll`, sem garantir atomicidade da invariante "exatamente um veículo ativo".
- `UserService.changePassword` (linhas ~82–95): salva a nova senha e, em seguida, revoga todos os refresh tokens em uma chamada separada (que possui sua própria `@Transactional` em `revokeAllForUser`, ou seja, em transação distinta).

Como `SimpleJpaRepository.save` já é `@Transactional` por método, cada chamada de `save`/`saveAll` confirma (commit) independentemente. Se a segunda escrita falhar (constraint, timeout de conexão, deadlock), a primeira já foi commitada.

## Impacto

- **Integridade de dados:** um `Refuel` pode ser gravado sem que o `currentKm` do veículo seja atualizado, gerando divergência entre odômetro e histórico de abastecimentos (impacto direto em métricas financeiras/consumo).
- **Invariante de domínio quebrada:** `setActiveVehicle` pode deixar o usuário com zero ou mais de um veículo ativo se uma das escritas do `saveAll` falhar no meio do lote.
- **Segurança:** uma troca de senha pode ser persistida sem revogar as sessões antigas — risco relevante quando a troca de senha é motivada por suspeita de comprometimento de conta.

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java` (`createRefuel`)
- `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java` (`setActiveVehicle`)
- `src/main/java/com/devappmobile/flowfuel/user/UserService.java` (`changePassword`)
- Testes correspondentes:
  - `src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTest.java`
  - `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java`
  - `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java`

## Requisitos Técnicos

- Anotar os três métodos de orquestração com `@Transactional` no nível do `@Service`.
- Garantir que a anotação `@Transactional` seja aplicada de forma a cobrir todas as escritas relevantes do método (atenção a chamadas a métodos `@Transactional` próprios de outras classes — propagação `REQUIRED` por padrão é suficiente).
- Não alterar a lógica de negócio existente, apenas o boundary transacional.
- Validar que nenhuma exception "engolida" silenciosamente impeça o rollback (verificar se exceptions de domínio são `RuntimeException`/unchecked, condição padrão para rollback do Spring).

## Passos de Implementação

1. Ler `RefuelService.createRefuel`, `VehicleService.setActiveVehicle` e `UserService.changePassword` para mapear todas as operações de escrita envolvidas.
2. Adicionar `@Transactional` (`org.springframework.transaction.annotation.Transactional`) em cada um dos três métodos.
3. Verificar se as classes já têm outras dependências/anotações que possam conflitar (ex.: proxies AOP, métodos `private`/`final` que `@Transactional` não intercepta — os métodos alvo já são públicos de `@Service`, então ok).
4. Confirmar que `RefreshTokenService.revokeAllForUser` (chamado por `changePassword`) participa da mesma transação por propagação padrão.
5. Rodar a suíte de testes existente para os três services.

## Critérios de Aceitação

- `RefuelService.createRefuel`, `VehicleService.setActiveVehicle` e `UserService.changePassword` estão anotados com `@Transactional`.
- Em caso de falha simulada na segunda escrita de cada fluxo, nenhuma escrita anterior permanece commitada (rollback completo).
- Testes existentes continuam passando sem alterações de comportamento no caminho feliz.
- Novos testes de regressão cobrem o cenário de falha parcial para os três métodos (ver Estratégia de Testes).

## Estratégia de Testes

- **Unit tests (Mockito):** para cada método, simular uma exception (ex.: `RuntimeException` ou `DataAccessException`) na segunda chamada de repositório e verificar que a primeira operação não é persistida (via `verify(repository, never())...` ou teste de integração com banco real para validar rollback).
- **Integration tests (`@SpringBootTest` + H2):** criar cenário onde a segunda escrita falha (ex.: mock de bean substituído por `@MockBean` que lança exception) e validar via `TestEntityManager`/repository que o estado do banco permanece inalterado (rollback).
- Cobrir especificamente:
  - `createRefuel`: falha ao salvar `Vehicle` → `Refuel` não deve persistir.
  - `setActiveVehicle`: falha no meio do `saveAll` → nenhum veículo deve ter `active` alterado.
  - `changePassword`: falha ao revogar refresh tokens → senha não deve ser alterada.

## Riscos

- Baixo risco geral — mudança aditiva (apenas anotação).
- Risco de mascarar lentamente uma transação muito longa se `createRefuel`/`setActiveVehicle` crescerem no futuro (manter escopo da transação enxuto).
- Atenção a possíveis efeitos colaterais não-transacionais dentro desses métodos (ex.: envio de e-mail/notificação) que não devem ser revertidos — confirmar que nenhum dos três métodos dispara side-effects externos hoje.

## Dependências

Nenhuma. Pode ser implementado e entregue de forma totalmente independente.

## Estimativa

0,5–1 dia (anotação + testes de regressão para os 3 métodos).

## Checklist

- [x] Analisar código atual
- [x] Implementar solução
- [x] Adicionar testes
- [x] Atualizar documentação
- [x] Executar testes de regressão
- [x] Abrir PR
