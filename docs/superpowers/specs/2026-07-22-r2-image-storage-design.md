# Imagens no Cloudflare R2 (substituindo Postgres)

## Contexto

Desde [2026-06-18-photo-storage-in-postgres-design.md](2026-06-18-photo-storage-in-postgres-design.md), as fotos (perfil de usuário e foto de veículo) são armazenadas como `bytea` na tabela `stored_files` do Postgres gerenciado pelo Neon (free tier). O trade-off aceito na época era compartilhar a cota de 0.5GB entre dados relacionais e imagens.

Esse trade-off deixou de se sustentar: a conta Neon está estourando a **cota de compute time** do plano free (não só armazenamento). Cada upload/download de imagem passa pelo compute do banco, e isso — mais do que o restante dos dados relacionais — é o principal suspeito de esgotar a cota. Decisão: tirar as imagens do Postgres e mover para Cloudflare R2, mantendo o Postgres (Neon) só para dados relacionais.

## Por que R2 (e não Supabase Storage ou manter Postgres)

- R2 tem egress gratuito — relevante porque imagens são o tipo de dado mais sensível a custo de banda.
- Trocar de banco (Neon → Supabase) não resolveria a causa raiz: o problema é guardar bytea num banco relacional, não o provedor. Ver [[neon-quota-root-cause]].
- A interface `StorageService` já isola essa decisão desde o design anterior — trocar a implementação é direto, sem tocar nos consumidores.

## Arquitetura

Nova implementação `R2StorageService implements StorageService`, substituindo `PostgresStorageService` como bean ativo. R2 é S3-compatível, então usamos o AWS SDK S3 (`software.amazon.awssdk:s3`) apontando para o endpoint do R2 (`https://<account_id>.r2.cloudflarestorage.com`).

Domínio público: como `flowfuel.app` ainda não está com o DNS na Cloudflare, usamos por enquanto a URL pública padrão do bucket (`https://pub-xxxx.r2.dev/<key>`). Quando o domínio for migrado para a Cloudflare, trocar para um Custom Domain (`images.flowfuel.app`) é só configuração — não deve exigir mudança de código além da env var de base URL.

### `R2StorageService`

- **`upload(file, key)`**: mantém o redimensionamento via Thumbnailator (512x512 max, JPEG ~85%, já existente) e faz `PutObject` no bucket R2 com a `key`.
- **`delete(key)`**: `DeleteObject` no bucket. Idempotente, mesmo comportamento atual.
- **`download`** é substituído por um método que devolve a URL pública (`String publicUrl(String key)`), já que a entrega passa a ser por redirect, não por bytes. A assinatura exata da interface `StorageService` é ajustada no plano de implementação.

### Mudança de contrato da API

`GET /users/{userId}/profile-picture` e `GET /vehicles/{id}/photo` passam de `ResponseEntity<byte[]>` (200 com bytes) para **HTTP 302 redirect** (`Location: <url pública do R2>`). Clientes que usam a URL como `src` de `<img>` continuam funcionando sem mudança — navegadores seguem redirect automaticamente. Clientes que esperavam bytes no corpo da resposta (ex.: chamadas diretas via HTTP client que não seguem redirect) precisam de ajuste — nenhum caso conhecido disso hoje no projeto.

### Configuração

Novas env vars (padrão do projeto — nunca hardcoded, seguindo o mesmo modelo de `JWT_SECRET`/`SPRING_DATASOURCE_*`):

```
CLOUDFLARE_R2_ACCOUNT_ID
CLOUDFLARE_R2_ACCESS_KEY_ID
CLOUDFLARE_R2_SECRET_ACCESS_KEY
CLOUDFLARE_R2_BUCKET
CLOUDFLARE_R2_PUBLIC_BASE_URL
```

## Migração dos dados existentes

Script one-off que lê todas as linhas de `stored_files` e faz upload de cada uma para o R2 usando a mesma `key`, preservando o mapeamento key → objeto já usado em `user_profile`/`vehicle` para referenciar a imagem. Por linha: se o upload falhar, loga a `key` com erro e continua as demais (não aborta o lote); ao final imprime um resumo (total, sucesso, falha).

Script roda manualmente contra prod **antes** do deploy que troca o bean ativo — se houver falhas no resumo, não prosseguir para o cutover até resolver.

## Rollout (sem downtime, sem perda de dado)

1. **Deploy 1**: adiciona `R2StorageService` + configuração, mas `PostgresStorageService` continua sendo o bean ativo — valida só que a integração compila e sobe, sem afetar produção.
2. Roda o script de migração contra prod (dados existentes → R2), confirma resumo sem falhas.
3. **Deploy 2**: troca o bean ativo para `R2StorageService`; controllers passam a fazer redirect 302.
4. Observação por alguns dias (Sentry já integrado no projeto, ver `application-prod.properties`) antes de remover `PostgresStorageService`, `StoredFile`, `StoredFileRepository` e a tabela `stored_files` numa entrega separada e futura — não faz parte deste escopo.

## Tratamento de erros

- Falha de upload pro R2 (rede, credencial inválida) → `BusinessRuleException`, mesmo padrão já usado para erro de imagem inválida.
- `key` sem objeto correspondente no R2 → `ResourceNotFoundException`, tratado pelo handler global existente (404 com `ProblemDetail`).
- Script de migração: erro por linha não aborta o lote (ver seção acima).

## Testes

- Unit tests para `R2StorageService` com mock do S3 client (upload, delete, publicUrl).
- Ajustar testes de integração de `UserController`/`VehicleController` que hoje esperam bytes no corpo — passam a verificar status 302 e header `Location` apontando para a URL do R2.
- Testes do script de migração: linha com sucesso, linha com falha (não aborta as seguintes), resumo final correto.

## Fora de escopo

- Custom Domain (`images.flowfuel.app`) — depende de migrar o DNS de `flowfuel.app` para a Cloudflare, que é um projeto separado.
- Remoção de `PostgresStorageService`/tabela `stored_files` — mantidos como backup até confirmar estabilidade em produção (entrega futura).
- Qualquer mudança no banco relacional (Neon/Supabase) — fora de escopo; este design resolve a causa raiz do estouro de quota sem precisar trocar o provedor do Postgres.

## Pontos de Atenção

- `[INFERIDO]` A causa do estouro de compute do Neon é atribuída ao tráfego de bytea de imagens — não foi possível confirmar com uma query direta de diagnóstico porque a própria quota já estava estourada no momento da investigação (`error: Your account or project has exceeded the compute time quota`). Se depois de mover as imagens pro R2 a quota continuar estourando, reabrir investigação.
