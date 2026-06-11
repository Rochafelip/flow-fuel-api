---
id: M5
phase: 2
priority: high
complexity: medium-high
estimate: 3-5d
status: pending
depends_on: [M4]
---

# M5 — Otimizar `DashboardService` (reduzir queries sequenciais e carregamento em memória)

## Objetivo

Reduzir o número de queries sequenciais e eliminar o carregamento de listas completas em memória no fluxo `GET /dashboard/vehicle/{id}`, o endpoint mais usado do app, usando a fórmula de consumo médio já reconciliada em [[M4-reconcile-average-consumption-formula]].

## Problema Atual

`DashboardService.buildDashboard` / `buildHybridBreakdown` (linhas ~37–118) executa, para cada `GET /dashboard/vehicle/{id}`:

- **Não-híbrido:** 5 queries (`countByVehicleId`, `getTotalSpentByVehicleId`, `findTopByVehicleIdOrderByRefuelDateDesc`, `getTotalEnergyByVehicleId`, `getAveragePricePerUnitByVehicleId`) + `findFullTankRefuelsByVehicleId` (carrega a **lista completa** de reabastecimentos "tanque cheio" do histórico do veículo).
- **Híbrido:** as 3 últimas são duplicadas para `FUEL` e `ELECTRIC` (até **9 queries** + 2 listas completas).

Para veículos com anos de histórico, `findFullTankRefuelsByVehicleId` cresce indefinidamente e é totalmente carregado em memória apenas para calcular uma média.

## Impacto

- **Risco de performance mais visível ao usuário** — endpoint mais usado do app, com latência crescente conforme o histórico de abastecimentos do veículo aumenta.
- Uso de memória crescente e não-limitado por requisição.
- Sem cache, cada acesso ao dashboard repete todo o trabalho mesmo que os dados não tenham mudado desde a última requisição.

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java` (`buildDashboard`, `buildHybridBreakdown`, `calculateAverageConsumption`)
- `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardDTO.java`
- `src/main/java/com/devappmobile/flowfuel/dashboard/HybridBreakdownDTO.java`
- `src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java` (novas queries de projeção agregada)
- Novo DTO de projeção (ex.: `RefuelAggregateProjection` ou similar)
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java`
  - `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardControllerIntegrationTest.java`

## Requisitos Técnicos

1. **Combinar agregações simples em uma única query** com múltiplas projeções, ex.:
   ```java
   @Query("SELECT new com.devappmobile.flowfuel.dashboard.RefuelAggregateProjection(" +
          "COUNT(r), SUM(r.totalAmount), SUM(r.energyAmount), AVG(r.pricePerUnit)) " +
          "FROM Refuel r WHERE r.vehicle.id = :vehicleId")
   RefuelAggregateProjection getAggregates(@Param("vehicleId") Long vehicleId);
   ```
   Para o caso híbrido, a mesma query parametrizada por `refuelType` (`FUEL`/`ELECTRIC`).
2. **Consumo médio sem carregar lista completa:** usar a fórmula oficial documentada em [[M4-reconcile-average-consumption-formula]], implementada via query agregada (ex.: `SUM`/diferença de odômetro em SQL/JPQL) ou, alternativamente, limitar a janela (ex.: últimos N reabastecimentos "tanque cheio") via `Pageable`.
3. **(Opcional, pode virar item de Fase 3 separado se o escopo crescer demais):** cache de curto prazo via Spring Cache + TTL de poucos minutos para o dashboard, já que os dados não mudam a cada requisição.
4. Manter o contrato de resposta (`DashboardDTO`/`HybridBreakdownDTO`) inalterado — esta é uma otimização interna, não uma mudança de contrato de API.

## Passos de Implementação

1. Confirmar que [[M4-reconcile-average-consumption-formula]] está concluído e a fórmula oficial de consumo médio está documentada.
2. Mapear todas as queries atualmente executadas por `buildDashboard`/`buildHybridBreakdown` (5 a 9 queries + listas completas).
3. Criar projeção agregada (`record` ou classe) para `COUNT`/`SUM`/`AVG` combinados em uma query JPQL.
4. Implementar query agregada para consumo médio seguindo a fórmula oficial de M4 (sem carregar lista completa em memória) — ou aplicar `Pageable` para limitar a janela de reabastecimentos "tanque cheio" considerados.
5. Refatorar `DashboardService.buildDashboard`/`buildHybridBreakdown` para usar as novas queries agregadas, reduzindo o total de queries por requisição.
6. (Opcional) Avaliar e, se viável dentro da estimativa, implementar cache de curto prazo (`@Cacheable` + TTL) no método de entrada do dashboard.
7. Rodar testes existentes e adicionar testes de regressão para garantir paridade de valores com a implementação anterior.
8. Validar performance com dados de teste representando histórico extenso (ex.: centenas/milhares de reabastecimentos) — comparar número de queries (ex.: via log de SQL/Hibernate statistics) antes e depois.

## Critérios de Aceitação

- O número de queries por requisição em `GET /dashboard/vehicle/{id}` é reduzido (idealmente para 1-2 queries no caso não-híbrido, 2-4 no híbrido).
- Nenhuma lista completa de reabastecimentos é carregada em memória para calcular consumo médio (ou, se aplicável, a janela é explicitamente limitada via `Pageable`).
- Os valores retornados pelo endpoint (`DashboardDTO`/`HybridBreakdownDTO`) permanecem **idênticos** aos valores anteriores para os mesmos dados (paridade validada por testes).
- Contrato de API (`DashboardController`) inalterado.

## Estratégia de Testes

- **Testes de paridade:** para um conjunto de dados fixo (fixture com vários reabastecimentos, incluindo "tanque cheio" e híbrido FUEL/ELECTRIC), comparar a saída de `DashboardService` antes e depois da refatoração — devem ser idênticas.
- **Testes de unidade** para as novas queries de projeção agregada (`@DataJpaTest` com H2), cobrindo:
  - Veículo sem reabastecimentos (valores zerados/nulos tratados corretamente).
  - Veículo com poucos reabastecimentos.
  - Veículo híbrido com reabastecimentos `FUEL` e `ELECTRIC`.
- **Teste de regressão de contagem de queries:** usar Hibernate statistics (`SessionFactory.getStatistics()`) ou similar para asserir que o número de queries por chamada não excede um limite definido (ex.: ≤ 2 para não-híbrido).
- Rodar `DashboardControllerIntegrationTest` completo.

## Riscos

- **Médio-alto risco** — maior refatoração da Fase 2, toca lógica de cálculo financeiro/consumo exibida diretamente ao usuário; qualquer divergência na fórmula agregada vs. a fórmula Java original gera bug visível.
- Risco de a query agregada para consumo médio (envolvendo diferenças de odômetro entre reabastecimentos consecutivos "tanque cheio") ser complexa de expressar em JPQL puro — pode exigir `@Query(nativeQuery = true)` ou processamento híbrido (agregações simples em SQL + cálculo de consumo médio com janela limitada via `Pageable`).
- Cache (se implementado) introduz risco de dados "desatualizados" por até o TTL configurado — definir TTL curto e documentar.

## Dependências

**Depende de [[M4-reconcile-average-consumption-formula]]** — M4 deve ser resolvido antes ou em conjunto, pois define a fórmula oficial que a query agregada de M5 deve implementar.

## Estimativa

3–5 dias (M4 + M5 combinados: 4–6 dias).

## Checklist

- [ ] Analisar código atual
- [ ] Implementar solução
- [ ] Adicionar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
