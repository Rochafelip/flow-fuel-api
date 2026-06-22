# Roadmap de Implementação — FlowFuel API

> Estrutura de execução gerada a partir do [Roadmap de Implementação](../../Claude/Roadmap_Implementacao.md), baseado no [Relatório Técnico — Auditoria FlowFuel API](../../Claude/Relatorio_Tecnico.md). Cada item do roadmap foi transformado em uma task independente, pronta para implementação.

**Legenda de complexidade:** 🟢 Low · 🟡 Medium · 🔴 High

---

## Visão Geral

O roadmap está organizado em **4 fases**, priorizadas por impacto de negócio, risco técnico e dependências, buscando entrega rápida de valor com redução progressiva de risco:

```
Fase 1 (Crítico)        →  Fase 2 (Alta Prioridade)   →  Fase 3 (Média Prioridade)   →  Fase 4 (Débito Técnico)
A1, A2, A3                 M8, B5, M4+M5, (M6)            B6, M1, M7, M3, B1              M2, B3, B2, B4
~2-3 dias                  ~7-11 dias                     ~8-11 dias                       ~5-7 dias
```

**Total estimado:** ~4-6 semanas de esforço de engenharia (1 dev full-time), distribuído em sprints. Fases podem ser paralelizadas parcialmente por desenvolvedores diferentes desde que as dependências abaixo sejam respeitadas.

Cada fase é entregável e "shippable" de forma independente — não é necessário esperar o roadmap inteiro para colocar valor em produção.

---

## Ordem de Execução

### Fase 1 — Issues Críticas (Sprint 1)

Foco: integridade de dados, segurança de sessão e bug de produção que quebra o onboarding. Todos os itens são isolados, de baixo esforço e alto retorno.

| Ordem | Task | Descrição |
|---|------|-----------|
| 1 | [A1 — `@Transactional` em fluxos multi-entidade](phase-1/A1-transactional-boundaries.md) | Atomicidade em `RefuelService.createRefuel`, `VehicleService.setActiveVehicle`, `UserService.changePassword` |
| 2 | [A2 — Handler `DataIntegrityViolationException`](phase-1/A2-data-integrity-handler.md) | Corrige 500 espúrio em cadastro concorrente com mesmo e-mail |
| 3 | [A3 — Validador de link de ativação em prod](phase-1/A3-activation-link-validator.md) | Elimina fallback `localhost` em e-mails de ativação de produção |

**Entrega:** PR único ou 3 PRs pequenos, deploy imediato.

### Fase 2 — Issues de Alta Prioridade (Sprint 2)

Foco: fechar lacunas de validação relacionadas à Fase 1, e endereçar o risco de performance mais visível ao usuário (dashboard).

| Ordem | Task | Descrição |
|---|------|-----------|
| 4 | [M8 — `UserUpdateDTO` + `@Valid`](phase-2/M8-user-update-dto-validation.md) | DTO dedicado para `updateProfile`, sem campo `password` |
| 5 | [B5 — `LoginRequest` como record](phase-2/B5-login-request-record.md) | Padroniza DTO de login + habilita `@Valid` |
| 6 | [M4 — Reconciliar fórmula de consumo médio](phase-2/M4-reconcile-average-consumption-formula.md) | Remove query JPQL morta, documenta fórmula oficial |
| 7 | [M5 — Otimizar `DashboardService`](phase-2/M5-optimize-dashboard-service.md) | Reduz 5–9 queries sequenciais para 1–2 via projeções agregadas |
| 8 | [M6 — Rate limiting distribuído](phase-2/M6-distributed-rate-limiting.md) | Avaliar/decidir migração para backend compartilhado (Redis) |

**Entrega:** 4+5 em um PR pequeno (1,5 dia); 6+7 como PR maior dedicado (4-6 dias); 8 conforme decisão de produto/infra.

### Fase 3 — Melhorias de Média Prioridade (Sprint 3-4)

Foco: reduzir duplicação de código de segurança, fechar lacunas de teste, limpar modelagem de entidades. Prepara o terreno para o refactor da Fase 4 (M2).

| Ordem | Task | Descrição |
|---|------|-----------|
| 9 | [B6 — `AuthorizationHelper`](phase-3/B6-authorization-helper.md) | Extrai `ownsVehicle`/`ownsRefuel`/`ownsEvent` duplicados em 4 services |
| 10 | [M1 — `OpaqueTokenGenerator` + `AbstractOpaqueToken`](phase-3/M1-opaque-token-generator.md) | Unifica geração/hash de token opaco (refresh/reset/ativação) |
| 11 | [M7 — Remover cascade ALL](phase-3/M7-remove-cascade-all.md) | Remove `cascade=ALL, orphanRemoval=true` de `Vehicle.refuels`/`User.vehicles` |
| 12 | [M3 — SDK v2 + `S3Presigner`](phase-3/M3-s3-sdk-v2-migration.md) | Remove AWS SDK v1, cria testes de `S3StorageService` |
| 13 | [B1 — Unificar parse JWT](phase-3/B1-unify-jwt-parsing.md) | `tryParse` único, elimina duplo parse por requisição |

**Entrega:** itens podem ser feitos em paralelo por devs diferentes (9/13 são isolados; 10, 11, 12 tocam áreas distintas). Ordem sugerida: **9 → 10 → (11, 12, 13 em paralelo)**.

### Fase 4 — Débito Técnico & Refatoração (Sprint 5)

Foco: o refactor estrutural mais arriscado (M2), feito por último porque depende dos utilitários extraídos nas fases anteriores, seguido de limpeza final de baixo risco.

| Ordem | Task | Descrição |
|---|------|-----------|
| 14 | [M2 — Split de `UserService`](phase-4/M2-split-user-service.md) | Divide em `AuthService` + `UserProfileService` |
| 15 | [B3 — Fix NPE `addVehicle`/`removeVehicle`](phase-4/B3-fix-user-add-vehicle-npe.md) | Inicializa `User.vehicles` |
| 16 | [B2 — Remover dead code](phase-4/B2-remove-dead-code.md) | Remove métodos de repositório e overloads não utilizados |
| 17 | [B4 — Remover segredo JWT de dev](phase-4/B4-remove-dev-jwt-secret.md) | Remove segredo "dev-only" commitado |

**Entrega:** 14 como PR grande e isolado (feature branch de vida curta, testes verdes antes de merge); 15-17 podem ir juntos em um PR de cleanup final.

---

## Dependências Entre Tasks

```
A1 ──────────────────────────────────────────────────────────  (sem dependências)
A2 ──┬──────────────────────────────────────────────────────── (sem dependências)
     └──> M8 (lógico, não bloqueante)
A3 ──────────────────────────────────────────────────────────  (sem dependências)

B5 ──────────────────────────────────────────────────────────  (sem dependências)
M4 ──> M5 (M4 define a fórmula que M5 implementa de forma otimizada)
M6 ──────────────────────────────────────────────────────────  (depende de decisão de infra; pode ser adiado)

B6 ──┐
     ├──> M2 (ordem lógica: evita retrabalho no split do UserService)
M1 ──┘

M7 ──────────────────────────────────────────────────────────  (sem dependências; requer testes de regressão próprios)
M3 ──────────────────────────────────────────────────────────  (sem dependências; requer testes próprios de S3StorageService)
B1 ──────────────────────────────────────────────────────────  (sem dependências)

M2 ──> B2 (overloads de uploadProfilePicture só podem ser removidos após M2 atualizar os testes)
B3 ──────────────────────────────────────────────────────────  (sem dependências; agrupar com M2 por proximidade de arquivo)
B4 ──────────────────────────────────────────────────────────  (sem dependências)
```

**Dependência mais importante do roadmap:** ordem **B6 → M1 → M2** (Fase 3 antes de Fase 4). Fazer M2 antes de B6/M1 dobraria o esforço de refatoração do `UserService`.

---

## Cronograma Sugerido

| Sprint | Fase | Tasks | Duração estimada |
|--------|------|-------|-------------------|
| Sprint 1 | Fase 1 | A1, A2, A3 | 2-3 dias |
| Sprint 2 | Fase 2 | M8, B5, M4, M5, (M6 conforme decisão) | 7-11 dias |
| Sprint 3-4 | Fase 3 | B6, M1, M7, M3, B1 | 8-11 dias |
| Sprint 5 | Fase 4 | M2, B3, B2, B4 | 5-7 dias |

**Notas de execução:**

- **Quick wins primeiro:** Fase 1 inteira é de baixo esforço/alto impacto e deve ser tratada como prioridade de sprint atual, independentemente do restante do roadmap.
- **M6** é o único item com dependência externa real (infraestrutura Redis). Se não houver plano de scale-out de curto prazo, sugerimos rebaixar para backlog com gatilho explícito ("revisitar antes do próximo scale-out horizontal"), sem bloquear o roadmap.
- **Lacunas de teste identificadas no relatório** (S3StorageService, jobs de cleanup de reset/ativação, cenários de corrida de A2/A1) são fechadas **junto com** as tasks correspondentes (M3, M1, A1/A2) — não como item separado.

---

## Status Tracking

| # | ID | Task | Fase | Prioridade | Complexidade | Estimativa | Status | Depende de |
|---|----|------|------|------------|---------------|------------|--------|------------|
| 1 | A1 | [Transactional boundaries](phase-1/A1-transactional-boundaries.md) | 1 | critical | 🟢 Low | 0,5–1d | done | — |
| 2 | A2 | [Data integrity handler](phase-1/A2-data-integrity-handler.md) | 1 | critical | 🟢 Low | 0,5d | done | — |
| 3 | A3 | [Activation link validator](phase-1/A3-activation-link-validator.md) | 1 | critical | 🟢🟡 Low-Med | 0,5–1d | done | — |
| 4 | M8 | [UserUpdateDTO + @Valid](phase-2/M8-user-update-dto-validation.md) | 2 | high | 🟢🟡 Low-Med | 1d | pending | A2 |
| 5 | B5 | [LoginRequest record](phase-2/B5-login-request-record.md) | 2 | high | 🟢 Low | 0,5d | pending | — |
| 6 | M4 | [Reconciliar consumo médio](phase-2/M4-reconcile-average-consumption-formula.md) | 2 | high | 🟢🟡 Low-Med | 1d | pending | — |
| 7 | M5 | [Otimizar DashboardService](phase-2/M5-optimize-dashboard-service.md) | 2 | high | 🟡🔴 Med-High | 3–5d | pending | M4 |
| 8 | M6 | [Rate limit distribuído](phase-2/M6-distributed-rate-limiting.md) | 2 | medium | 🟡 Medium | 2–3d + infra | pending | decisão de infra |
| 9 | B6 | [AuthorizationHelper](phase-3/B6-authorization-helper.md) | 3 | medium | 🟢 Low | 1d | pending | — |
| 10 | M1 | [OpaqueTokenGenerator](phase-3/M1-opaque-token-generator.md) | 3 | medium | 🟡🔴 Med-High | 3–4d | pending | — |
| 11 | M7 | [Remover cascade ALL](phase-3/M7-remove-cascade-all.md) | 3 | medium | 🟡 Medium | 1–2d | pending | — |
| 12 | M3 | [SDK v2 + S3Presigner](phase-3/M3-s3-sdk-v2-migration.md) | 3 | medium | 🟡 Medium | 2–3d | pending | — |
| 13 | B1 | [Unificar parse JWT](phase-3/B1-unify-jwt-parsing.md) | 3 | medium | 🟢 Low | 0,5d | pending | — |
| 14 | M2 | [Split UserService](phase-4/M2-split-user-service.md) | 4 | low | 🔴 High | 4–6d | pending | M1, B6 |
| 15 | B3 | [Fix NPE addVehicle](phase-4/B3-fix-user-add-vehicle-npe.md) | 4 | low | 🟢 Low | 0,25d | pending | — |
| 16 | B2 | [Remover dead code](phase-4/B2-remove-dead-code.md) | 4 | low | 🟢 Low | 0,5d | pending | M2 |
| 17 | B4 | [Remover segredo dev](phase-4/B4-remove-dev-jwt-secret.md) | 4 | low | 🟢 Low | 0,25d | pending | — |

**Status possíveis:** `pending` · `in-progress` · `blocked` · `deferred` · `done`

> Para atualizar o status de uma task, edite tanto o frontmatter (`status:`) do arquivo da task quanto a coluna correspondente nesta tabela.

---

## Referências

- [Roadmap de Implementação original](../../Claude/Roadmap_Implementacao.md)
- [Relatório Técnico — Auditoria FlowFuel API](../../Claude/Relatorio_Tecnico.md)
