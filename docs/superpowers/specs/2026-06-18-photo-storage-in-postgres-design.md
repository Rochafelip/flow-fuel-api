# Foto de perfil armazenada no Postgres (substituindo S3/B2)

## Contexto

A API está em produção no Fly.io (ver [docs/deploy.md](../../deploy.md)), com Postgres gerenciado no Neon (free tier). O upload de foto de perfil hoje depende do Backblaze B2 (`S3StorageService`), que não foi configurado — `B2_S3_*` ficaram vazios, então upload de foto falha em produção.

Para uso pessoal (1-2 usuários), criar conta num provedor de storage externo a mais é fricção desnecessária. Decisão: guardar a foto como `bytea` no próprio Postgres do Neon, eliminando a dependência externa.

## Por que não manter S3/B2

- Mais uma conta/serviço pra gerenciar, mais um secret pra configurar.
- Para 1-2 usuários trocando de foto raramente, o volume de dados é mínimo — não justifica um object storage dedicado.

## Trade-off aceito

O Neon free tier tem só 0.5GB de armazenamento total (banco + fotos). Para não competir essa cota, as imagens são redimensionadas e recomprimidas antes de salvar (ver abaixo). Se o uso crescer muito além de 1-2 usuários, reavaliar volta para object storage externo — a interface `StorageService` já isola essa decisão, então trocar a implementação no futuro é direto.

## Arquitetura

Nova implementação `PostgresStorageService implements StorageService`, substituindo `S3StorageService`. Nenhuma mudança na assinatura da interface nem nos consumidores (`UserProfileService`, `UserController`) além da remoção do conceito de URL externa assinada.

### Tabela `stored_files` (migration Flyway `V8__create_stored_files.sql`)

```sql
CREATE TABLE stored_files (
    key VARCHAR(255) PRIMARY KEY,
    content_type VARCHAR(100) NOT NULL,
    data BYTEA NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
```

Tabela genérica (não amarrada a "foto de perfil" especificamente), mantendo a mesma flexibilidade que a interface `StorageService` já tinha com S3 — qualquer key pode ser usada para qualquer tipo de arquivo armazenado.

### `PostgresStorageService`

- **`upload(file, key)`**: lê os bytes do `MultipartFile`, redimensiona via [Thumbnailator](https://github.com/coobird/thumbnailator) (nova dependência Maven, Java puro, sem binários nativos) para no máximo 512x512 mantendo aspect ratio, recomprime sempre como JPEG (qualidade ~85%). Faz upsert (`INSERT ... ON CONFLICT (key) DO UPDATE SET content_type = ?, data = ?`) na tabela `stored_files`.
- **`download(key)`**: `SELECT content_type, data FROM stored_files WHERE key = ?`. Lança `ResourceNotFoundException` se a key não existir (hoje o `S3StorageService` deixa a exceção do SDK propagar como erro genérico — padronizamos para uma exceção de domínio já usada no projeto).
- **`delete(key)`**: `DELETE FROM stored_files WHERE key = ?`. Idempotente (não lança erro se a key não existir), igual ao comportamento atual.
- **`getUrl(key)`**: removido da interface (ver seção "Mudança de contrato" abaixo) — não existe mais URL externa pré-assinada.

### Mudança de contrato da API

O DTO de resposta do perfil (`UserResponseDTO`) e do upload (`UploadResponse`) perdem o campo `profilePictureUrl` (URL assinada com expiração de 15 min, conceito que só existia por causa do S3). Resta apenas `profilePicture`, o path interno (`/auth/{userId}/profile-picture`), que já existia como fallback e passa a ser o único meio de acesso à imagem.

`StorageService.getUrl(String key)` é removido da interface — não há mais necessidade dele com armazenamento local.

### Limpeza de código morto

- Remover `S3StorageService` e `S3StorageServiceTest`.
- Remover dependência do AWS SDK S3 do `pom.xml` (se não usada em outro lugar do projeto).
- Remover envs `B2_S3_*`/`B2_BUCKET_NAME` do `render.yaml`, `fly.toml`, `.env.example`, `.env.prod.example` e da seção "Pontos de atenção" do [docs/deploy.md](../../deploy.md).

## Tratamento de erros

- Falha no resize (Thumbnailator não reconhece o formato/arquivo corrompido) → `BusinessRuleException` ("Arquivo de imagem inválido ou corrompido"), mesma camada que já valida tipo/tamanho em `UserProfileService.uploadProfilePictureResponse`.
- `download` com key inexistente → `ResourceNotFoundException`, tratado pelo handler global de exceções já existente no projeto (retorna 404 com `ProblemDetail`).
- Upsert falhando por motivo de infraestrutura (conexão com banco caiu, etc.) → propaga como erro 500 genérico, sem tratamento especial (mesmo comportamento atual).

## Testes

- Substituir `S3StorageServiceTest` por `PostgresStorageServiceTest`, usando Testcontainers Postgres (padrão já adotado no projeto para testes de integração de banco).
- Casos a cobrir: upload com resize efetivo (imagem grande → confirma dimensão final ≤512x512), upload sobrescrevendo key existente (upsert), download de key existente, download de key inexistente (`ResourceNotFoundException`), delete idempotente.
- Ajustar `UserProfileServiceTest` e o teste de integração do `UserController` removendo asserts sobre `profilePictureUrl`/`signedUrl`.

## Fora de escopo

- Migração de fotos já existentes do B2 para o Postgres — não há fotos reais em produção hoje (upload nunca funcionou, pois `B2_S3_*` nunca foi configurado), então não há dados a migrar.
- Cache/CDN na frente do endpoint de download — desnecessário para 1-2 usuários.
