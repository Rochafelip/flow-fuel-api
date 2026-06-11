---
id: M4
phase: 2
priority: high
complexity: low-medium
estimate: 1d
status: pending
depends_on: []
---

# M4 — Reconciliar fórmula de consumo médio (remover query JPQL morta)

## Objetivo

Eliminar a divergência entre duas implementações da métrica "consumo médio" — uma query JPQL morta (`RefuelRepository.getAverageConsumptionByVehicleId`) e a implementação Java ativa (`DashboardService.calculateAverageConsumption`) — documentando a fórmula oficial em um único lugar antes de qualquer otimização (ver [[M5-optimize-dashboard-service]]).

## Problema Atual

Existem duas implementações diferentes da mesma métrica de negócio:

- **Query JPQL morta** (`RefuelRepository.getAverageConsumptionByVehicleId`): faz `SUM(kmSinceLastRefuel) / SUM(energyAmount)` sobre todos os reabastecimentos "tanque cheio".
- **Método Java ativo** (`DashboardService.calculateAverageConsumption`): itera pares consecutivos de reabastecimentos "tanque cheio" e soma `(odometer atual − odometer anterior) / soma de energyAmount`, **ignorando** o campo `kmSinceLastRefuel` já persistido.

A query morta sugere que a fórmula evoluiu e ninguém removeu a versão antiga.

## Impacto

- Risco real de alguém reativar a query antiga (ex.: numa otimização futura para evitar carregar listas inteiras, como proposto em M5) e obter um número **diferente** do que o frontend já exibe hoje — divergência de métricas financeiras/consumo visível ao usuário.
- Ambiguidade sobre qual é a "fórmula oficial" de consumo médio dificulta qualquer otimização futura (bloqueia [[M5-optimize-dashboard-service]] de forma segura).

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java` (`getAverageConsumptionByVehicleId`)
- `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java` (`calculateAverageConsumption`)
- Documentação técnica (recomenda-se documentar a fórmula oficial — ex.: em `Claude/DOCUMENTACAO_TECNICA.md` ou comentário Javadoc no método)
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java`

## Requisitos Técnicos

- Decidir e documentar qual fórmula é a oficial: a fórmula ativa em `DashboardService.calculateAverageConsumption` (baseada em `(odometer atual − odometer anterior) / soma de energyAmount`) é a que o frontend já consome — **manter esta como oficial**, salvo decisão explícita em contrário.
- Remover `RefuelRepository.getAverageConsumptionByVehicleId` (dead code), já que não é referenciado por nenhum service.
- Documentar a fórmula oficial (com exemplo numérico, se possível) próximo à implementação em `DashboardService`, para servir de referência ao trabalho de otimização de M5.

## Passos de Implementação

1. Confirmar (grep) que `RefuelRepository.getAverageConsumptionByVehicleId` não é referenciado em nenhum lugar do código de produção (apenas testes, se houver).
2. Remover o método `getAverageConsumptionByVehicleId` de `RefuelRepository` (e a query JPQL associada).
3. Adicionar Javadoc/comentário em `DashboardService.calculateAverageConsumption` documentando explicitamente a fórmula oficial: `(odometer do refuel atual − odometer do refuel "tanque cheio" anterior) / soma de energyAmount no intervalo`.
4. Se houver testes que exercitem a query removida, removê-los ou adaptá-los.
5. Rodar testes de `DashboardServiceTest` para garantir que nada dependia (mesmo indiretamente) do método removido.

## Critérios de Aceitação

- `RefuelRepository.getAverageConsumptionByVehicleId` não existe mais no código.
- A fórmula de consumo médio usada por `DashboardService` está documentada (Javadoc/comentário) como a fórmula oficial do produto.
- Nenhuma mudança de comportamento observável no endpoint `/dashboard/vehicle/{id}` (valores retornados permanecem idênticos).
- Testes existentes de `DashboardServiceTest` continuam passando.

## Estratégia de Testes

- Rodar a suíte completa de `DashboardServiceTest` e `DashboardControllerIntegrationTest` antes e depois da remoção, comparando resultados — não deve haver mudança de valores retornados.
- Adicionar um teste unitário que documente explicitamente a fórmula oficial com um exemplo numérico simples (2-3 reabastecimentos "tanque cheio" com valores conhecidos → consumo médio esperado), servindo como "contrato" para a futura otimização de M5.

## Riscos

- Muito baixo risco — remoção de código morto + documentação.
- Risco de a "fórmula oficial" não ser de fato a desejada pelo produto (divergência de negócio) — se houver dúvida, validar com stakeholder de produto antes de documentar como oficial (não deve bloquear a remoção do dead code, apenas a decisão de documentação).

## Dependências

Nenhuma para iniciar. **Deve ser resolvido antes ou junto com [[M5-optimize-dashboard-service]]** — M4 define a fórmula que a nova query agregada de M5 vai implementar. Recomenda-se executar M4 primeiro e só então iniciar M5.

## Estimativa

1 dia.

## Checklist

- [ ] Analisar código atual
- [ ] Implementar solução
- [ ] Adicionar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
