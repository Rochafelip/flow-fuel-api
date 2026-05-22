# Discussão Estratégica: FlowFuel - Arquitetura, Produto e Infraestrutura

## Contexto Inicial

Você tem um **backend funcional** em Spring Boot 3.5 + JWT + Postgres com módulos bem separados. Seu projeto é um caso clássico de **"produto pessoal/early-stage buscando entrar no mobile"**. 

⚠️ **Aviso crítico**: quase todo conselho de "arquitetura moderna" da internet é veneno nesse estágio.

---

## Diagnóstico do Cenário Atual

### Realidade
Você tem um backend que resolve o problema técnico. **O risco número 1 não é arquitetural — é não ter usuários reais usando.**

### ✅ Pontos Fortes

- Stack mainstream e contratável (Spring Boot + Postgres + JWT) com comunidade enorme
- Domínio organizado por feature (user/vehicle/refuel/dashboard) — **isso já é "modular monolith"**. Mantenha.
- Swagger, testes (80), Flyway, DTOs, paginação — está acima da média para projetos solo

### ⚠️ Pontos Fracos (Dívidas Silenciosas)

- **JWT stateless** sem refresh token e sem rotação — clássica armadilha de segurança
- **Upload de imagem** direto no filesystem da app — não escala, quebra em deploy contêinerizado
- **Sem observabilidade básica** (logs estruturados, métricas, error tracking)
- **Sem ambiente de staging** — todo deploy é "deploy direto no usuário"
- **Nenhuma validação de mercado** — você não sabe se as pessoas querem isso

**O ponto fraco mais crítico é o último.**

---

## Riscos Técnicos e Operacionais

1. **Risco de produto > risco técnico**
   - Você pode gastar 4 meses construindo o app Android perfeito e descobrir que ninguém quer registrar abastecimento manualmente
   - Esse é o vilão histórico desse nicho

2. **JWT sem refresh = armadilha mobile**
   - Você não pode pedir login a cada 1h no mobile
   - Vai acabar emitindo JWT de 30 dias = problema real de segurança

3. **Acoplamento Android ↔ REST**
   - Uma vez no ar, mudar payload quebra usuários em versões antigas
   - Problema operacional mais subestimado por quem vem do backend

4. **Upload de foto + sem storage externo**
   - Deploy quebra os dados
   - Migrar depois é doloroso

5. **Banco sem backup automatizado**
   - Mata projeto pessoal num único acidente

6. **Custo operacional emocional**
   - Stack complexa solo = você para de mexer em 3 meses

---

## Decisões Arquiteturais Recomendadas

### Monolito Modular (Mantenha!)

Você já tem. **Não toque.**

Microserviços para um app de controle de combustível em MVP é **autossabotagem**. Até unicórnios de 2025 (Shopify, GitHub, Basecamp) defendem monolito publicamente. Microserviços resolvem problemas **organizacionais** (múltiplos times), não técnicos.

### REST (Sem BFF, Sem GraphQL)

Para 4 domínios e 1 cliente (Android):
- **BFF** = overkill
- **GraphQL** = hype mal aplicado

REST é simples, suficiente, e você já tem.

### Versionamento de API Desde Já

Você já tem `/api/v1` — perfeito.

**Regra**: nunca remova campo, nunca mude tipo, só adicione. Mudança incompatível? Cria `/v2`.

### Refresh Token AGORA (Antes do Android)

Trocar esquema de auth depois do app no ar é uma das piores migrações possíveis.

**Setup correto**:
- Access token curto (15min)
- Refresh token longo (30d, rotacionável, revogável no banco)

Esse investimento se paga 10x.

### Storage Externo Desde o Dia 1

S3, R2 (Cloudflare, barato) ou equivalente.

**Nunca** use disco local nem que seja "só pra testar".

---

## Estratégia de Backend

- **Camadas**: continue por feature (vertical slice), não por tipo técnico. Você já está certo.
- **DTOs e ProblemDetail**: ótimo, mantenha. RFC 7807 é padrão em 2025.
- **Paginação**: padronize cedo (`page/size/sort`) e **nunca** retorne lista nua.
- **Idempotência**: header `Idempotency-Key` em POSTs (criar abastecimento). Evita duplicatas quando app perde rede.
- **Cache HTTP**: ETags e `Cache-Control` em GETs do dashboard. Economiza bateria/dados do celular.
- **WebSocket**: ❌ Não. Polling com cache é suficiente.
- **Offline-first no backend**: você precisa de:
  - Timestamps (`updatedAt`, `deletedAt`) em tudo
  - Endpoints de sincronização incremental (`?updatedAfter=...`)
  - Isso viabiliza offline-first depois

---

## Estratégia de Android

### Stack Recomendada (2025)

- **Kotlin + Jetpack Compose** — padrão, sem dúvida. XML é legado.
- **MVVM + ViewModel + StateFlow** — dominante e suficiente.
  - ❌ MVI adiciona boilerplate sem ganho real
- **Clean Architecture "light"** — não completa
  - Data layer: Retrofit + Room
  - Domain layer: simples
  - UI layer: Compose + ViewModel
  - ❌ Sem 17 interfaces
- **Modularização**: ❌ não no início
  - Comece com 1 módulo
  - Modularize quando build > 3min ou tiver 2+ devs
- **Persistência**: Room (sem discussão)
- **HTTP**: Retrofit + OkHttp + Kotlinx Serialization
- **DI**: Hilt (Koin é alternativa, mas Hilt é padrão)
- **Imagens**: Coil
- **Navegação**: Navigation Compose

### O Que Diferencia Apps Profissionais

Não é arquitetura. É:
- Tratamento de erro real (sem `Toast("Erro")`)
- Estados consistentes (loading/empty/error)
- Comportamento em rede ruim
- Dark mode
- Acessibilidade básica
- Tempo de cold start

### ⚠️ Decisão Crítica: Offline-First?

**Decida agora.** É a decisão mais difícil de reverter depois.

**Para FlowFuel**:
- Offline-first é correto (usuário abastece no posto, pode não ter sinal)
- MAS custa 2-3x mais para implementar

**Se quiser MVP rápido**: online-first com cache agressivo, migra depois (com dor moderada).

---

## Estratégia de Infraestrutura

### Para Este Estágio (MVP)

- **Hosting**: Railway, Fly.io ou Render. **❌ Não AWS.**
  - AWS no início = 40% tempo configurando IAM vs construindo produto
  - Custo de oportunidade absurdo

- **Banco**: Postgres gerenciado (Neon, Supabase, Railway managed)
  - Backup automático fora da caixa
  - **Nunca rode Postgres sem backup automatizado**

- **Storage de arquivos**: Cloudflare R2 (egress grátis) ou S3

- **Docker**: sim, mas simples
  - 1 Dockerfile apenas
  - ❌ Sem docker-compose elaborado

- **Kubernetes**: ❌ Não
  - Você não precisa, não vai precisar nos próximos 2 anos
  - Quando precisar, use gerenciado (EKS/GKE)

- **CI/CD**: GitHub Actions
  - Build + testes + deploy automático para staging (main)
  - Deploy manual para prod
  - Isso é tudo.

- **Ambientes**: local + staging + prod
  - ⚠️ Pular staging = erro que vai te morder

- **Terraform**: ❌ Não agora
  - Vale com >5 recursos cloud manuais frequentes
  - Por enquanto, é cerimônia

- **Secrets**: variáveis de ambiente do provider
  - Railway/Fly têm nativo
  - ❌ Sem Vault, sem KMS

- **Custo alvo**: US$ 10–30/mês até ter tração
  - Mais que isso = errou

---

## Estratégia de Escalabilidade

### A Regra: Escala Quando Dói, Não Antes

- **Postgres**: aguenta tranquilamente até centenas de milhares de usuários
  - Fato comprovado em produção

- **Redis**: ❌ Só quando tiver gargalo real de leitura
  - No início, cache em memória da app (Caffeine) resolve

- **CDN**: vale para imagens
  - R2/Cloudflare já vem com CDN nativo

- **Filas** (RabbitMQ/Kafka): ❌ Não
  - Use tabela do Postgres como fila (`SELECT FOR UPDATE SKIP LOCKED`)
  - Funciona até dezenas de milhares de jobs/dia
  - Kafka é decisão de empresa, não de produto

- **O que quebra primeiro** (antes de pensar em escala):
  1. Query N+1
  2. Endpoint sem paginação
  3. Índice faltando

---

## Estratégia de Observabilidade

### Mínimo Viável no Dia 1 do Android em Produção

1. **Logs estruturados em JSON**
   - Logback com encoder JSON
   - Custo: 1h de setup
   - Benefício: enorme

2. **Sentry** ✅ Vale muito a pena
   - Plano free cobre MVP
   - Integra Android + Spring Boot
   - Te avisa de crash sem usuário falar nada

3. **Uptime monitoring**: UptimeRobot ou Better Stack (gratuito)

4. **Métricas**: ❌ Só se gostar de dashboard
   - Spring Actuator + Prometheus + Grafana Cloud (free tier)
   - Se não, adia

5. **Tracing distribuído**: ❌ Skip
   - Você tem 1 serviço. Nada para tracear.

### Erro Comum
Adotar 5 ferramentas e não olhar nenhuma.

**Regra**: 1 dashboard que você abre toda semana > 10 que você nunca abre.

---

## Estratégia de Produto

### Antes de Qualquer Linha de Código Android

1. **Defina a métrica do norte**
   - O que é "sucesso" para FlowFuel?
   - Ex: "Usuário registra ≥3 abastecimentos no primeiro mês"
   - Sem isso, você não sabe se está vencendo

2. **Teste o problema, não a solução**
   - Usuário resolve com planilha? App concorrente? Ou não resolve?
   - Se "não resolve", talvez o problema não doa o suficiente

3. **MVP de verdade**
   - Login + cadastrar veículo + registrar abastecimento + ver consumo médio
   - **Mais nada**
   - Foto de perfil, múltiplos veículos, gráficos, exportação = v1.1+

4. **O que mata apps desse nicho: fricção de registro manual**
   - Pense agora em como reduzir:
     - OCR de cupom?
     - Integração com bomba?
     - Widget na home?
     - Notificação proativa?
   - Esse é seu diferencial real — não a stack

---

## Estratégia de Evolução Futura

### O Que Preservar Como Opção (Sem Implementar Agora)

- **iOS depois** → API limpa, sem lógica de UI no backend
- **Features sociais/compartilhamento** → modelar `userId` consistentemente (já está fazendo)
- **Monetizar** → adicione `subscriptionTier` em User cedo (sempre `FREE` por enquanto)
  - Migrar depois é trivial assim
- **Dados externos** (preço combustível, posto) → adicione campos opcionais `postoId`, `precoUnitario`
  - Já abre esse caminho

---

## O Que Implementar AGORA (Antes do Android)

1. ✅ Refresh token + rotação + revogação
2. ✅ Storage externo de arquivos (R2/S3)
3. ✅ Backup automatizado do Postgres (gerenciado já resolve)
4. ✅ Ambiente de staging com domínio próprio
5. ✅ Logs estruturados + Sentry no backend
6. ✅ Campos de sincronização (`updatedAt`, `deletedAt`/soft delete) em todas entidades do app
7. ✅ Idempotency-Key em POSTs críticos (criar abastecimento)
8. ✅ Política de versionamento documentada (você tem `/v1`, formalize a regra)
9. ✅ README/ADR curto com 5 decisões principais:
   - Monolito modular
   - REST
   - JWT + refresh
   - Postgres
   - Offline-first sim/não

---

## O Que NÃO Implementar Agora

- ❌ Microserviços, Kubernetes, Service Mesh
- ❌ GraphQL, BFF, WebSocket
- ❌ Kafka, RabbitMQ, Redis
- ❌ Terraform, ArgoCD, GitOps
- ❌ OpenTelemetry, tracing distribuído
- ❌ MVI, Clean Architecture completa, modularização Android
- ❌ Feature flags elaborado (LaunchDarkly) — boolean em `config.yml` resolve
- ❌ A/B testing (você não tem usuários ainda)
- ❌ Internacionalização (lance em pt-BR primeiro)
- ❌ Login social (email/senha basta no MVP)

---

## O Que Adiar

| O Quê | Por Quê | Quando |
|-------|---------|--------|
| Push notifications | Só depois de ter retenção mensurável | v1.x |
| Multi-tenancy / contas familiares | Complexidade desnecessária | v2 |
| Exportação CSV/PDF | Baseado em pedido real | v1.x |
| Dashboard com gráficos elaborados | Comece com 3 números (consumo médio, km/L, custo/mês) | v1.x |
| App iOS | Só com 100+ usuários ativos no Android | v2+ |

---

## Roadmap: Próximas 8–12 Semanas

### Semana 1–2: Backend Essencial
- Refresh token + rotação + revogação
- R2/S3 setup
- Ambiente staging
- Sentry integration

### Semana 3: Decisão Crítica
- Offline-first sim/não?
- Documentar decisão
- Ajustar API se necessário

### Semana 4–10: Android MVP
- Login → veículo → abastecer → dashboard simples
- Nada mais

### Semana 11–12: Beta Real
- Beta fechado com 10–20 pessoas reais
- Métricas
- Iterar

### ⚠️ Checkpoint Crítico
Se semana 12 não tem usuários reais usando: **pare e reavalie produto, não código.**

---

## Erros Mais Comuns (O Que Startups Fazem Errado)

- ❌ Construir 6 meses antes de mostrar para usuário
- ❌ Escolher stack pelo "vai escalar para 1M usuários" antes de ter 10
- ❌ Adotar microserviços sozinho
- ❌ Cair na armadilha multi-plataforma (Flutter/RN) sem necessidade
- ❌ Investir em CI/CD elaborado, dashboards bonitos, backlog perfeito em vez de falar com usuários
- ❌ Tratar segurança como "depois" (JWT eterno, sem rate limiting, sem refresh)
- ❌ Não ter backup
- ❌ Confundir arquitetura limpa com arquitetura complexa
- ❌ Modularizar Android antes da hora

---

## O Que Empresas Maduras Fazem Diferente

- ✅ Tratam decisões reversíveis como reversíveis (fazem rápido, ruim, refazem)
  - Decisões irreversíveis recebem tempo desproporcional
  - (API pública, schema de auth, modelo de cobrança)

- ✅ Documentam decisões com ADRs curtos (1 página)
  - Você vai esquecer por que decidiu X em 6 meses

- ✅ Mantêm monolito modular até a dor organizacional ser concreta

- ✅ Investem em observabilidade proporcional ao número de usuários, não ao hype

- ✅ Têm staging que parece produção

- ✅ Cuidam de developer experience
  - Build rápido, testes rápidos, deploy rápido
  - Isso compõe juros

---

## Decisão Final Recomendada

### Sua Arquitetura para os Próximos 12 Meses

#### Backend
- Monolito modular Spring Boot (o que você tem)
- Refresh token + rotação
- R2/S3 para arquivos
- Sentry para erros
- Postgres gerenciado
- Ambiente staging

#### API
- REST `/v1` versionado
- ProblemDetail (RFC 7807)
- Paginação padrão
- Idempotency-Key
- Campos de sincronização (`updatedAt`, `deletedAt`)

#### Android
- Kotlin + Jetpack Compose
- MVVM + ViewModel + StateFlow
- Hilt (DI)
- Retrofit + Room
- Módulo único
- Clean Architecture "light"
- **Decisão crítica antes de começar**: offline-first sim/não?

#### Infraestrutura
- Railway ou Fly.io
- Docker simples
- GitHub Actions
- US$ 10–30/mês

#### Observabilidade
- Logs JSON
- Sentry
- Uptime monitor
- Resto adia

#### Produto
- MVP brutalmente pequeno
- Beta com 10–20 pessoas reais
- Métricas claras
- Foco em usuários, não em features

---

## Princípio Fundamental

**A arquitetura certa para o estágio atual é a mais simples que ainda preserva opções futuras importantes.**

Tudo que você construir além disso é **dívida disfarçada de qualidade**.

---

*Última atualização: 2026-05-22*
