# Deploy em produção (Fly.io + Supabase)

Setup de produção para uso pessoal (1-2 usuários), 24/7, sem custo de servidor dedicado.

## Arquitetura

- **API**: Fly.io, região `gru` (São Paulo), 1 máquina `shared-cpu-1x` / **512MB RAM**, sempre ativa (`min_machines_running = 1`, sem auto-stop).
- **Banco**: Supabase (Postgres), região `sa-east-1`, conexão via Session Pooler (Supavisor) — a conexão direta (porta 5432 no host `db.<projeto>.supabase.co`) só resolve em IPv6 nesse projeto (sem add-on de IPv4), por isso o app usa o host `aws-0-sa-east-1.pooler.supabase.com`, que também é IPv4. Migrado do Neon em 2026-07-23 (dump/restore via `pg_dump`/`pg_restore`, ver histórico da migração).
- Config da máquina: [fly.toml](../fly.toml).
- Imagem: [Dockerfile](../Dockerfile) (build multi-stage Maven + JRE Alpine).

URL pública: `https://flowfuel-api.fly.dev` (HTTPS automático via Fly).

## Por que não Render free / Oracle Cloud / Railway

- **Render free**: dorme após ~15min de inatividade (cold start de 30-60s) e o Postgres free expira após inatividade — não confiável para "sempre online".
- **Oracle Cloud Free Tier**: conta nova foi recusada na verificação ("high risk") sem cartão.
- **Fly.io**: também exigiu verificação de conta com cartão (`fly.io/high-risk-unlock`), mas sem outras barreiras. Foi o caminho seguido.

## Passo a passo (do zero)

### 1. Banco de dados (Supabase)
1. Criar conta em supabase.com, criar projeto (região mais próxima — `sa-east-1` no nosso caso).
2. Em **Connect → Session pooler**, copiar a connection string (formato `postgresql://postgres.<ref>:<senha>@aws-0-<regiao>.pooler.supabase.com:5432/postgres`). Usar o **Session pooler**, não a conexão direta (IPv6-only nesse plano) nem o Transaction pooler (não suporta prepared statements, usados pelo Hibernate).

### 2. Instalar a CLI do Fly e logar
```bash
curl -L https://fly.io/install.sh | sh
# adicionar ao ~/.bashrc:
export FLYCTL_INSTALL="$HOME/.fly"
export PATH="$FLYCTL_INSTALL/bin:$PATH"

flyctl auth login
```

### 3. Verificação de conta (obrigatório para contas novas)
Contas novas do Fly.io são marcadas "high risk" e bloqueadas até cadastrar um cartão em `fly.io/high-risk-unlock`. Sem isso, `flyctl launch` falha com `Error: Your account has been marked as high risk`.

### 4. Criar o app a partir do `fly.toml`
```bash
flyctl launch --no-deploy --copy-config --yes
```

### 5. Configurar os secrets
```bash
flyctl secrets set \
  SPRING_DATASOURCE_URL="jdbc:postgresql://aws-0-<regiao>.pooler.supabase.com:5432/postgres?sslmode=require" \
  SPRING_DATASOURCE_USERNAME="postgres.<ref-do-projeto-supabase>" \
  SPRING_DATASOURCE_PASSWORD="<senha-do-banco-supabase>" \
  JWT_SECRET="$(openssl rand -hex 32)" \
  FLOWFUEL_RATE_LIMIT_ENABLED=false \
  MANAGEMENT_HEALTH_MAIL_ENABLED=false \
  MAIL_ENABLED=true \
  MAIL_HOST=smtp.gmail.com \
  MAIL_PORT=587 \
  MAIL_USERNAME=flowfuelapp@gmail.com \
  MAIL_PASSWORD="<senha-de-app-do-gmail>" \
  MAIL_FROM="flowfuelapp@gmail.com" \
  PASSWORD_RESET_LINK_BASE_URL="<url-do-frontend-em-producao>/reset-password"
```

(`MAIL_PASSWORD` é uma "senha de app" do Google — requer verificação em duas etapas ativa na conta e é gerada em `myaccount.google.com/apppasswords`; não é a senha normal da conta. `PASSWORD_RESET_LINK_BASE_URL` é obrigatório em `prod`/`staging`: se ausente ou apontando para `localhost`, a aplicação falha o boot de propósito — ver `PasswordResetLinkValidator`.)

Trocamos de SendGrid para Gmail SMTP em 2026-08-31 depois que o free tier do SendGrid esgotou os créditos e passou a rejeitar autenticação (`451 Authentication failed: Maximum credits exceeded`), derrubando o cadastro de novas contas (ver "Envio de email" nos Pontos de atenção do [README](../README.md#deploy-produção)). Gmail é gratuito e suficiente para o volume de 1-2 usuários deste projeto, mas mais sujeito a cair em spam (sem domínio próprio/DMARC) e tem limite diário (~500 emails/dia) — se o volume crescer, considerar voltar a um provedor transacional dedicado (SendGrid, Brevo, etc.) com domínio verificado.

### 6. Deploy
```bash
flyctl deploy
```

### 7. Redis para rate-limiting (Upstash via Fly)

O rate-limiting (bucket4j) precisa de um Redis real para operar em modo enforce — sem ele, o `RateLimitFilter` fica em fail-open (loga warning, deixa passar). Hoje produção não tem Redis provisionado.

```bash
flyctl redis create   # escolher regiao gru, plano gratuito/menor
```

O comando imprime a `REDIS_URL` de conexão. Configurar como secret (nunca em `[env]` do `fly.toml`, que é texto plano versionado):

```bash
flyctl secrets set REDIS_URL="redis://<host-upstash>:<porta>" -a flowfuel-api
```

Como o rate-limiting foi desligado no passo 5 (`FLOWFUEL_RATE_LIMIT_ENABLED=false`) por falta de Redis — e essa env var na verdade não corresponde à propriedade lida pelo código (`flowfuel.rate-limit.enabled` lê `RATE_LIMIT_ENABLED`, não `FLOWFUEL_RATE_LIMIT_ENABLED`) — esse secret nunca chegou a desligar nada de fato; o rate-limiting já estava habilitado por padrão e em fail-open por falta de Redis. Após configurar `REDIS_URL`, remover o secret órfão por clareza:

```bash
flyctl secrets unset FLOWFUEL_RATE_LIMIT_ENABLED -a flowfuel-api
```

**Validação pós-deploy**: como o filtro é fail-open, um `REDIS_URL` incorreto não gera erro visível — requisições continuam passando normalmente. Confirmar manualmente que o rate-limiting está de fato ativo fazendo requisições repetidas a um endpoint limitado (ex. `/api/v1/auth/login`) até obter `429` com header `Retry-After`.

### 8. Cloudflare R2 (armazenamento de imagens)

Imagens (foto de perfil, foto de veículo) ficam no Cloudflare R2, não no Postgres —
ver [docs/superpowers/specs/2026-07-22-r2-image-storage-design.md](superpowers/specs/2026-07-22-r2-image-storage-design.md).

```bash
npm install -g wrangler
wrangler login
wrangler r2 bucket create flowfuel-images
wrangler r2 bucket dev-url enable flowfuel-images
```

O Access Key ID / Secret Access Key (credenciais S3-compatible) não são gerados pelo
Wrangler — criar em **Cloudflare dashboard → R2 → Manage R2 API Tokens → Create API Token**
(permissão *Object Read & Write*, escopo no bucket `flowfuel-images`).

```bash
flyctl secrets set \
  CLOUDFLARE_R2_ACCOUNT_ID="<account-id>" \
  CLOUDFLARE_R2_ACCESS_KEY_ID="<access-key-id>" \
  CLOUDFLARE_R2_SECRET_ACCESS_KEY="<secret-access-key>" \
  CLOUDFLARE_R2_BUCKET="flowfuel-images" \
  CLOUDFLARE_R2_PUBLIC_BASE_URL="https://pub-<hash>.r2.dev"
```

Domínio próprio (`images.flowfuel.app`) fica pendente até `flowfuel.app` estar com o DNS
na Cloudflare — hoje as imagens são servidas pela URL pública padrão do R2 (`*.r2.dev`).

## Problemas encontrados e correções (histórico real do primeiro deploy)

1. **`fly.toml` inválido** — health check sem `type` (`http`/`tcp`). Corrigido (depois sobrescrito pelo próprio `flyctl launch`, que regenerou o arquivo num formato válido sem precisar do campo).
2. **OOM kill com 256MB de RAM** — a JVM com Spring Security + JPA + AWS S3 SDK + Sentry não cabe em 256MB mesmo limitando o heap a 75% (`-XX:MaxRAMPercentage=75`). A máquina entrava em loop de crash/restart. **Correção**: subir `[[vm]] memory` para `512mb` no [fly.toml](../fly.toml).
3. **Rate limiting exige Redis** — `RateLimitingConfig` tenta conectar em `redis://localhost:6379` por padrão (`REDIS_URL` env var), que não existe no Fly. **Correção**: desligar via `FLOWFUEL_RATE_LIMIT_ENABLED=false` (flag já existia no código, usada também nos testes de integração). Alternativa não aplicada: provisionar Redis externo (ex. Upstash) e setar `REDIS_URL`.
4. **`/actuator/health` retornando DOWN por causa do Mail health indicator** — com `MAIL_ENABLED=false` o envio de email é desligado, mas o Spring Boot ainda autoconfigura o `MailHealthIndicator` (porque `spring.mail.host` está definido, mesmo vazio), e ele falha por falta de credenciais. **Correção**: `MANAGEMENT_HEALTH_MAIL_ENABLED=false`.
5. **App fora do ar após adicionar `PasswordResetLinkValidator` (2026-08-31)** — o validador (fail-fast em `prod`/`staging` se o link de reset de senha apontar pra `localhost`) subiu sem o secret `PASSWORD_RESET_LINK_BASE_URL` configurado no Fly, então caía sempre no default de dev e derrubava o boot. **Correção**: setar o secret (ver passo 5). No mesmo dia, o SendGrid também esgotou os créditos do free tier (`451 Authentication failed: Maximum credits exceeded`) e, como `AuthService.register` não tratava falha de envio de email, todo cadastro de conta nova passou a devolver 500. **Correção**: `register` agora loga o erro de envio em vez de propagá-lo (a conta é criada normalmente mesmo se o email falhar), e o provedor SMTP foi trocado de SendGrid para Gmail.

## Pontos de atenção / pendências

- **Rate limiting está em fail-open em produção** (sem Redis provisionado, ver passo 7) — os endpoints de auth (`/login`, `/register`, `/forgot-password`, `/resend-activation`, `/activate`) não têm proteção contra brute-force até que o Redis do passo 7 seja provisionado e `REDIS_URL` configurada.
- **Envio de e-mail (ativação de conta e redefinição de senha)**: configurar via os secrets `MAIL_*` do passo 5 (Gmail SMTP). Sem eles (`MAIL_ENABLED=false`, default do [application.properties](../src/main/resources/application.properties#L65)), o código/link só vai para o log da aplicação. Gmail é gratuito mas tem limite diário (~500/dia) e mais chance de cair em spam — reavaliar se o volume de usuários crescer.
- **Migração Neon → Supabase (2026-07-23)**: dump completo do Neon (`pg_dump --schema=public --no-owner --no-acl --format=custom`) e restore no Supabase (`pg_restore --clean --if-exists`), rodado via Docker (`postgres:17`, sem instalar client localmente). Contagem de linhas conferida em todas as 12 tabelas antes do cutover. Secrets `SPRING_DATASOURCE_*` do Fly atualizados para o Supabase e app redeployado com sucesso (Flyway validou as 11 migrations sem diffs). **O projeto Neon não foi apagado** — manter como backup por alguns dias até validar estabilidade em produção, depois desprovisionar.
- **Senha do Postgres do Neon e do Supabase foram expostas em texto puro numa conversa antes de serem usadas.** Recomendado resetar a senha do banco no painel do Supabase (Settings → Database → Reset database password) por precaução, e atualizar o secret `SPRING_DATASOURCE_PASSWORD` no Fly em seguida.
- **Upload de foto de perfil** agora usa o próprio Postgres (tabela `stored_files`, ver `docs/superpowers/specs/2026-06-18-photo-storage-in-postgres-design.md`) — sem dependência externa de storage.
- **Imagens no R2, não mais no Postgres**: `PostgresStorageService`/tabela `stored_files` ficam como backup temporário pós-migração (ver design de 2026-07-22); remover numa entrega futura depois de confirmar estabilidade em produção.
- **Auto-deploy do GitHub Actions (`flyctl launch`) falhou ao tentar setar `FLY_API_TOKEN`** nos secrets do repositório (sem permissão da CLI `gh` configurada). Isso foi corrigido depois: [.github/workflows/fly-deploy.yml](../.github/workflows/fly-deploy.yml) já dispara `flyctl deploy` a cada push em `main`, usando o secret `FLY_API_TOKEN`. [.github/workflows/ci.yml](../.github/workflows/ci.yml) ainda referencia Deploy Hooks do Render (`RENDER_DEPLOY_HOOK_*`) e não é usado nesse caminho — é resquício do setup anterior e candidato a limpeza.

## Comandos úteis

```bash
flyctl status -a flowfuel-api        # estado da máquina e health checks
flyctl logs -a flowfuel-api --no-tail # logs (não usar sem --no-tail em scripts, é streaming)
flyctl secrets list -a flowfuel-api  # ver quais secrets estão setados (não mostra valores)
flyctl deploy                        # redeploy manual após mudanças no código ou fly.toml
```
