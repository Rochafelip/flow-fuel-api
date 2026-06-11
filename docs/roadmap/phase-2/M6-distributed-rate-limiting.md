---
id: M6
phase: 2
priority: medium
complexity: medium
estimate: 2-3d + infra
status: pending
depends_on: []
---

# M6 — Rate limiting com backend compartilhado (avaliar/decidir)

## Objetivo

Avaliar e, se houver plano de scale-out horizontal no curto/médio prazo, migrar o `RateLimitFilter` (Bucket4j) de armazenamento em memória local para um backend compartilhado (ex.: Redis via `bucket4j-redis`), garantindo rate limiting efetivo em múltiplas instâncias.

## Problema Atual

`RateLimitFilter.java` mantém os buckets do Bucket4j em um `ConcurrentHashMap` **local** (já documentado como limitação conhecida no Javadoc da própria classe). Em deploy horizontal (múltiplas instâncias atrás de um load balancer), cada instância mantém seu próprio contador.

## Impacto

- Um atacante distribuindo requisições entre instâncias **multiplica efetivamente o limite** (ex.: 5 tentativas/min × N instâncias) nos endpoints protegidos: `/auth/login`, `/auth/forgot-password`, `/auth/register`, `/auth/resend-activation`.
- **Não é urgente na topologia atual** (instância única no Render), mas é um **bloqueador de escalabilidade horizontal** — precisa estar resolvido antes de qualquer scale-out.

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/config/RateLimitFilter.java`
- `src/main/java/com/devappmobile/flowfuel/config/RateLimitingConfig.java`
- `pom.xml` (nova dependência `bucket4j-redis` + cliente Redis, ex.: Lettuce/Jedis)
- Configuração de infraestrutura (provisionamento de Redis — fora do código)
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterIntegrationTest.java`

## Requisitos Técnicos

- **Decisão prévia (produto/infra):** confirmar se há plano de scale-out horizontal no curto/médio prazo.
  - **Se SIM:** prosseguir com a migração para `bucket4j-redis` (ou backend compartilhado equivalente).
  - **Se NÃO:** adiar para backlog com gatilho explícito ("revisitar antes do próximo scale-out horizontal") — não implementar agora, apenas documentar a decisão.
- Caso prossiga:
  - Adicionar dependência `bucket4j-redis` (e cliente Redis compatível) ao `pom.xml`.
  - Configurar `RateLimitingConfig` para usar um `ProxyManager` baseado em Redis em vez do `ConcurrentHashMap` local.
  - Provisionar instância Redis (Render add-on ou serviço externo) — ação de infraestrutura, não apenas código.
  - Garantir fallback gracioso se Redis estiver indisponível (decidir: fail-open vs. fail-closed para rate limiting — recomenda-se fail-open para não bloquear todos os usuários por indisponibilidade do Redis, mas registrar/alertar).

## Passos de Implementação

1. **Levantar decisão de produto/infraestrutura:** existe plano de scale-out horizontal nos próximos meses?
2. Se a decisão for **adiar**: documentar no roadmap/backlog com o gatilho "revisitar antes do próximo scale-out horizontal" e marcar este item como `status: deferred` (não como `done`).
3. Se a decisão for **prosseguir**:
   - Provisionar Redis (infra).
   - Adicionar `bucket4j-redis` ao `pom.xml`.
   - Refatorar `RateLimitingConfig`/`RateLimitFilter` para usar `ProxyManager` Redis-backed.
   - Configurar string de conexão Redis via env var (`REDIS_URL` ou similar), seguindo padrão 12-factor já usado no projeto.
   - Implementar estratégia de fallback caso Redis fique indisponível.
   - Atualizar `RateLimitFilterIntegrationTest` para validar comportamento com backend compartilhado (pode usar Testcontainers Redis ou embedded Redis para testes).

## Critérios de Aceitação

- **Se adiado:** decisão documentada em local visível (README do roadmap / backlog), com gatilho explícito de quando revisitar.
- **Se implementado:** rate limit é efetivo mesmo com múltiplas instâncias simultâneas acessando o mesmo backend Redis (validado em teste de integração com múltiplos "buckets" simulando instâncias diferentes).
- Endpoints protegidos (`/auth/login`, `/auth/forgot-password`, `/auth/register`, `/auth/resend-activation`) continuam retornando `429` + `Retry-After` conforme já implementado.
- Comportamento de fallback (Redis indisponível) é definido e testado.

## Estratégia de Testes

- **Se implementado:**
  - Teste de integração simulando duas "instâncias" (dois `ProxyManager`/clients apontando para o mesmo Redis) compartilhando o limite de um mesmo IP — o limite deve ser respeitado de forma agregada.
  - Teste de fallback: Redis indisponível → comportamento definido (fail-open com log de alerta, por exemplo) é validado.
  - Regressão: `RateLimitFilterIntegrationTest` existente continua passando (adaptado para o novo backend, possivelmente via Testcontainers).
- **Se adiado:** nenhum teste necessário; apenas atualização de documentação/backlog.

## Riscos

- **Único item do roadmap com dependência externa real de infraestrutura** (provisionamento de Redis).
- Se implementado sem necessidade real (sem scale-out planejado), adiciona complexidade operacional (mais um serviço para monitorar) sem benefício imediato.
- Risco de fail-closed mal configurado bloquear todos os usuários em caso de indisponibilidade do Redis — decisão de fallback deve ser explícita e revisada com a equipe.

## Dependências

Depende de **decisão de infraestrutura/produto** (plano de scale-out). Não bloqueia nem é bloqueado por outros itens do roadmap — pode ser adiado para Fase 3 ou backlog sem impacto nas demais tasks.

## Estimativa

2–3 dias de código + provisionamento de Redis (variável conforme infraestrutura), **se decidido prosseguir**. Custo zero se adiado para backlog.

## Checklist

- [ ] Levantar decisão de produto/infraestrutura (scale-out planejado?)
- [ ] Analisar código atual
- [ ] Implementar solução (ou documentar adiamento com gatilho)
- [ ] Adicionar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
