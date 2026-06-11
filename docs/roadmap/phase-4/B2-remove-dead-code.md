---
id: B2
phase: 4
priority: low
complexity: low
estimate: 0.5d
status: pending
depends_on: [M2]
---

# B2 — Remover métodos de repositório/overloads mortos

## Objetivo

Remover métodos de repositório não utilizados (dead code) e os overloads legacy de `uploadProfilePicture`, reduzindo o tamanho do codebase e a confusão para novos desenvolvedores.

## Problema Atual

Os seguintes métodos foram identificados como não utilizados (dead code):

- `VehicleRepository.existsById(Long)` — já herdado de `JpaRepository`, redeclaração redundante.
- `RefuelRepository.existsById(Long)` — idem.
- `VehicleRepository.existsByLicensePlateAndUserId` — não referenciado em nenhum service.
- `RefuelRepository.findByVehicleIdAndOdometerBetween` — não referenciado.
- `UserService.uploadProfilePicture(Long, MultipartFile, boolean)` / `uploadProfilePicture(Long, MultipartFile)` — comentados como "backwards-compatible... used by existing tests".

## Impacto

- Codebase maior do que o necessário, com métodos que sugerem uso/contrato que não existe mais.
- Confusão para novos desenvolvedores que podem assumir que esses métodos são usados em algum fluxo.
- Os overloads de `uploadProfilePicture` representam dívida técnica explícita (mantidos apenas por testes antigos).

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleRepository.java` (`existsById`, `existsByLicensePlateAndUserId`)
- `src/main/java/com/devappmobile/flowfuel/refuel/RefuelRepository.java` (`existsById`, `findByVehicleIdAndOdometerBetween`)
- `src/main/java/com/devappmobile/flowfuel/user/UserService.java` ou sucessores `AuthService`/`UserProfileService` (overloads de `uploadProfilePicture`) — **nota:** se [[M2-split-user-service]] já remover esses overloads como parte do split, esta task cobre apenas os métodos de repositório remanescentes.
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java` / repository tests
  - `src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTest.java` / repository tests
  - `src/test/java/com/devappmobile/flowfuel/user/*` (se overloads ainda não removidos por M2)

## Requisitos Técnicos

- Confirmar via `grep` em `src/main` e `src/test` que cada método candidato não é referenciado antes de remover.
- Remover apenas métodos confirmados como não utilizados — se algum teste ainda referenciar um overload de `uploadProfilePicture`, atualizar o teste para usar `uploadProfilePictureResponse` (deve já ter sido feito em [[M2-split-user-service]]; esta task cobre apenas o que sobrar).
- Os métodos de repositório (`VehicleRepository`/`RefuelRepository`) **podem ser removidos a qualquer momento** — não dependem de M2. Agrupar nesta limpeza final é uma decisão de organização do roadmap, não uma dependência técnica real para esses itens específicos.

## Passos de Implementação

1. `grep -rn "existsById" src/main/java/com/devappmobile/flowfuel/vehicle/` e confirmar que `VehicleRepository.existsById(Long)` é redeclaração redundante de `JpaRepository.existsById`.
2. Repetir para `RefuelRepository.existsById`.
3. `grep -rn "existsByLicensePlateAndUserId"` em todo o projeto (`src/main` e `src/test`) — remover se não houver uso.
4. `grep -rn "findByVehicleIdAndOdometerBetween"` em todo o projeto — remover se não houver uso.
5. Verificar se [[M2-split-user-service]] já removeu `uploadProfilePicture(Long, MultipartFile, boolean)` / `uploadProfilePicture(Long, MultipartFile)`. Se ainda existirem, removê-los agora e atualizar testes que os referenciam para `uploadProfilePictureResponse`.
6. Remover os métodos confirmados de `VehicleRepository`/`RefuelRepository`.
7. Rodar a suíte completa de testes (`mvn test`) para confirmar que nada quebra.
8. Rodar build completo (`mvn compile`) para garantir que nenhuma referência remanescente causa erro de compilação.

## Critérios de Aceitação

- `VehicleRepository.existsById(Long)`, `RefuelRepository.existsById(Long)`, `VehicleRepository.existsByLicensePlateAndUserId`, `RefuelRepository.findByVehicleIdAndOdometerBetween` não existem mais no código.
- Overloads `uploadProfilePicture(Long, MultipartFile, boolean)` / `uploadProfilePicture(Long, MultipartFile)` não existem mais (removidos aqui ou confirmados como já removidos em M2).
- Build (`mvn compile`) e suíte de testes (`mvn test`) passam sem erros.

## Estratégia de Testes

- Não são necessários novos testes — esta é uma remoção de código morto.
- **Regressão:** rodar a suíte completa do projeto após cada remoção (ou em lote, seguido de rollback seletivo se algo quebrar) para garantir que nenhum método removido era, na verdade, usado de forma não-óbvia (ex.: via reflection, Spring Data query derivation interno).
- Atenção especial a `existsById`: embora pareça redundante com `JpaRepository.existsById`, confirmar que a assinatura/comportamento não foi customizado de forma sutil antes de remover.

## Riscos

- Muito baixo risco — remoção de código não referenciado, validada por `grep` + build + testes.
- Único risco real: remover um overload de `uploadProfilePicture` que ainda seja referenciado por um teste não migrado em M2 — mitigado rodando a suíte completa antes do merge.

## Dependências

**Depende de [[M2-split-user-service]]** para os overloads de `uploadProfilePicture` (só podem ser removidos com segurança depois que M2 atualizar os testes que os referenciam). Os métodos de repositório podem ser removidos a qualquer momento, mas faz sentido agrupar em uma única limpeza final pós-M2.

## Estimativa

0,5 dia.

## Checklist

- [ ] Analisar código atual
- [ ] Confirmar (grep) que métodos candidatos não são usados
- [ ] Implementar solução (remoção)
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
