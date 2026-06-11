---
id: B6
phase: 3
priority: medium
complexity: low
estimate: 1d
status: pending
depends_on: []
---

# B6 — Extrair `AuthorizationHelper` (ownsVehicle/ownsRefuel/ownsEvent)

## Objetivo

Extrair a lógica de checagem de propriedade (`ownsVehicle`/`ownsRefuel`/`ownsEvent`/`ownsResource`), hoje duplicada em quatro services, para um componente único (`AuthorizationHelper`), reduzindo o risco de um novo service esquecer essa validação.

## Problema Atual

`VehicleService`, `RefuelService`, `VehicleEventService` e `DashboardService` reimplementam, cada um, a mesma lógica:

```java
vehicle.getUser().getId().equals(user.getId())
```

(ou variações equivalentes para `ownsRefuel`/`ownsEvent`). O padrão de autorização está **correto e aplicado consistentemente** em todos os pontos de acesso a recursos por veículo — o problema é puramente de duplicação (DRY).

## Impacto

- Qualquer evolução futura da regra de autorização (ex.: suporte a veículos compartilhados entre usuários, papéis/admin) precisa ser replicada em 4 lugares — alto risco de um deles ser esquecido.
- Aumenta a superfície de manutenção e o risco de um novo service (ex.: futuro `UserProfileService`/`AuthService` da Fase 4) reimplementar a checagem de forma sutilmente diferente ou esquecê-la.

## Arquivos Afetados

- Novo arquivo: `src/main/java/com/devappmobile/flowfuel/common/AuthorizationHelper.java` (ou pacote equivalente, seguindo a convenção `common`)
- `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`
- `src/main/java/com/devappmobile/flowfuel/refuel/RefuelService.java`
- `src/main/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventService.java`
- `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java`
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java`
  - `src/test/java/com/devappmobile/flowfuel/refuel/RefuelServiceTest.java`
  - `src/test/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventServiceTest.java`
  - `src/test/java/com/devappmobile/flowfuel/dashboard/DashboardServiceTest.java`
  - Novo: `AuthorizationHelperTest`

## Requisitos Técnicos

- Criar `@Component AuthorizationHelper` (ou classe utilitária estática, conforme convenção do projeto — preferir `@Component` para facilitar mock em testes) com métodos como:
  ```java
  public boolean ownsVehicle(User user, Vehicle vehicle) {
      return vehicle.getUser().getId().equals(user.getId());
  }
  ```
  e equivalentes para `ownsRefuel`/`ownsEvent` (que tipicamente delegam para `ownsVehicle` via `refuel.getVehicle()`/`event.getVehicle()`).
- Substituir todas as ocorrências duplicadas nos 4 services por chamadas ao `AuthorizationHelper`.
- Manter o comportamento de exception (`ForbiddenOperationException`) exatamente como está hoje — o helper deve encapsular apenas a checagem booleana ou, alternativamente, métodos que já lançam a exception (`ensureOwnsVehicle(...)`), conforme padrão mais usado nos services atuais.

## Passos de Implementação

1. Localizar todas as implementações de `ownsVehicle`/`ownsRefuel`/`ownsEvent`/checagens equivalentes nos 4 services (`grep -rn "getUser().getId()"`).
2. Criar `AuthorizationHelper` com os métodos consolidados, no pacote `common` (seguindo a organização "package by feature" + pacotes transversais já documentada).
3. Decidir a assinatura exata dos métodos com base no padrão mais comum hoje (ex.: retornar `boolean` vs. lançar exception diretamente) — manter consistência com `findOwned`/`ensureSelf` já existentes.
4. Substituir, um service por vez, as implementações locais pelas chamadas ao `AuthorizationHelper` (injeção via construtor).
5. Rodar os testes de cada service após a substituição.
6. Adicionar `AuthorizationHelperTest` cobrindo os casos positivo/negativo de propriedade.

## Critérios de Aceitação

- Não existe mais lógica de checagem de propriedade duplicada em `VehicleService`, `RefuelService`, `VehicleEventService`, `DashboardService` — todos delegam ao `AuthorizationHelper`.
- Comportamento de autorização (quem pode acessar o quê) permanece **idêntico** ao atual — mesmas exceptions, mesmos códigos HTTP.
- Testes existentes dos 4 services continuam passando sem alteração de expectativas de autorização.
- Novo `AuthorizationHelperTest` cobre `ownsVehicle`/`ownsRefuel`/`ownsEvent` (casos positivo e negativo).

## Estratégia de Testes

- **Unit test do `AuthorizationHelper`:** casos onde o usuário é dono do recurso (retorna `true`/não lança) e onde não é (retorna `false`/lança `ForbiddenOperationException`).
- **Regressão nos 4 services:** rodar a suíte completa de testes unitários e de integração de `vehicle`, `refuel`, `vehicleevent`, `dashboard` — nenhuma mudança de comportamento esperada.
- Cobrir especificamente o caso de acesso de um usuário a um recurso de outro usuário (`403 Forbidden`) em pelo menos um teste de integração por domínio.

## Riscos

- Muito baixo risco — refatoração mecânica de extração de método duplicado, sem mudança de regra de negócio.
- Atenção a pequenas variações sutis entre as 4 implementações atuais (ex.: null-checks diferentes) — mapear todas antes de unificar para não perder um caso de borda específico de algum service.

## Dependências

Nenhuma dependência técnica. **Precede [[M2-split-user-service]]** (ordem lógica, não técnica) — fazer B6 antes de M2 evita que o padrão de autorização precise ser duplicado/refatorado novamente quando `UserService` for dividido em `AuthService`/`UserProfileService`.

## Estimativa

1 dia.

## Checklist

- [ ] Analisar código atual
- [ ] Implementar solução
- [ ] Adicionar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
