# Provisionar Redis para Rate-Limiting (dev + produção)

## Contexto

O rate-limiting com bucket4j + Redis já está implementado e testado no código:

- `src/main/java/com/devappmobile/flowfuel/config/RateLimitingConfig.java`
- `src/main/java/com/devappmobile/flowfuel/config/RateLimitFilter.java`
- Testes de integração com Testcontainers: `src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterIntegrationTest.java`

O filtro já é fail-open: se o Redis não estiver disponível, a requisição passa normalmente (log de warning). Hoje **nenhum Redis real está provisionado**:

- `docker-compose.yml` não tem serviço Redis → dev local sempre em fail-open.
- `fly.toml` não provisiona Redis → produção sempre em fail-open (rate-limiting silenciosamente desligado).

A configuração já lê a URL via `flowfuel.rate-limit.redis-url=${REDIS_URL:redis://localhost:6379}` (`application.properties:81-84`). Não é necessária nenhuma mudança de código Java — este trabalho é puramente de infraestrutura/configuração.

## Objetivo

Provisionar Redis real para dev local e produção, para que o rate-limiting passe a operar de fato (modo enforce), não apenas fail-open.

## Fora de escopo

- Alterar `RateLimitFilter` / `RateLimitingConfig` ou os limites por endpoint.
- Persistência/durabilidade do Redis (cache de rate-limit é efêmero por natureza).
- CI/Testcontainers — já funcionam de forma independente e não dependem desta mudança.

## Design

### 1. Dev local — `docker-compose.yml`

Adicionar serviço `redis`:

```yaml
redis:
  image: redis:7-alpine
  container_name: flowfuel-redis
  ports:
    - "6379:6379"
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 5s
    timeout: 5s
    retries: 10
```

Sem volume — cache de rate-limit não precisa persistir entre restarts.

No serviço `api`:
- Adicionar `redis` em `depends_on`, com `condition: service_healthy` (mesmo padrão já usado para `db`).
- Adicionar variável de ambiente `REDIS_URL: redis://redis:6379`.

Nenhuma mudança em `.env.prod.example` é necessária (REDIS_URL tem default funcional via `redis://redis:6379` fixo no compose, não depende de secret local).

### 2. Produção — Fly.io (Upstash Redis)

Provisionamento via Upstash Redis integrado ao Fly (`fly redis create` ou `fly ext redis create`), na região `gru` (mesma do app), plano gratuito/menor disponível.

A `REDIS_URL` resultante deve ser configurada como **secret** do Fly (`fly secrets set REDIS_URL=...`), seguindo o mesmo padrão já usado para `JWT_SECRET` — não deve ir para `[env]` em `fly.toml` (que é texto plano versionado).

Nenhuma alteração em `fly.toml` é necessária além de documentação — a env var é resolvida via secret, e a aplicação já lê `REDIS_URL` do ambiente.

**Ação que requer aprovação explícita do usuário no momento da execução** (não será automatizada pelo plano): rodar `fly redis create` / `fly ext redis create` e `fly secrets set REDIS_URL=...` contra a infraestrutura real do Fly.io. O plano de implementação deve deixar este passo documentado como comando a ser executado manualmente (ou com confirmação explícita), não como parte de um script automático.

### 3. Documentação

Atualizar comentário de cabeçalho do `docker-compose.yml` e possivelmente um trecho no README do projeto (ou `docs/`) explicando:
- Como subir Redis localmente via compose.
- Os comandos `flyctl` para provisionar Upstash Redis e configurar o secret em produção.

## Plano de testes

- Dev local: `docker compose --env-file .env.prod up --build` e confirmar que o serviço `redis` sobe healthy e a API loga conexão Redis bem-sucedida (sem warnings de fail-open) ao fazer requisições repetidas em `/api/v1/auth/login` até disparar 429.
- Produção: após provisionar e configurar o secret, validar manualmente (fora do escopo de testes automatizados) que requisições repetidas a um endpoint limitado retornam 429 com header `Retry-After`.

## Riscos / Pontos de atenção

- Provisionar recursos pagos/gerenciados no Fly.io é uma ação que afeta infraestrutura compartilhada — requer confirmação explícita do usuário antes de executar.
- Se o secret `REDIS_URL` não for configurado corretamente em produção, o comportamento observável será idêntico ao atual (fail-open silencioso) — não há erro visível, então a verificação pós-deploy é importante.
