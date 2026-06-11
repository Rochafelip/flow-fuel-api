---
id: M3
phase: 3
priority: medium
complexity: medium
estimate: 2-3d
status: pending
depends_on: []
---

# M3 — Consolidar para AWS SDK v2 (`S3Presigner`), remover SDK v1

## Objetivo

Eliminar a dependência do AWS SDK v1 (`com.amazonaws:aws-java-sdk-s3`), usada hoje apenas para gerar URLs pré-assinadas, migrando para `S3Presigner` do AWS SDK v2 — e, como pré-requisito, criar a cobertura de testes inexistente para `S3StorageService`.

## Problema Atual

`S3StorageService.java` inicializa **dois clientes AWS em paralelo**:
- `software.amazon.awssdk.services.s3.S3Client` (AWS SDK v2) — usado para upload/download/delete.
- `com.amazonaws.services.s3.AmazonS3` (AWS SDK v1) — usado **apenas** para gerar URL pré-assinada (`getUrl()`).

Isso:
- Dobra a superfície de configuração/credenciais (`StaticCredentialsProvider` v2 + `AWSStaticCredentialsProvider` v1).
- Adiciona ~15MB de dependência do SDK v1 inteiro só por essa funcionalidade.
- `getUrl()` tem um **fallback silencioso** (`catch (Exception e) { /* fallthrough */ }`) que pode mascarar erros de configuração de URL pré-assinada.

**Lacuna de teste identificada no relatório:** não há testes de `S3StorageService` hoje (lógica de presigned URL com dois SDKs diferentes — área propensa a bugs silenciosos).

## Impacto

- Artefato de build maior (~15MB extra de dependência).
- Configuração de credenciais duplicada (dois conjuntos de credenciais para o mesmo provedor).
- Erros de configuração de presigned URL podem ser silenciosamente mascarados pelo `catch (Exception e) { /* fallthrough */ }`, dificultando diagnóstico em produção.
- Ausência de testes nesta classe é um ponto cego de qualidade — qualquer regressão na geração de URLs (ex.: foto de perfil, anexos) só seria percebida em produção.

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/storage/S3StorageService.java`
- `src/main/java/com/devappmobile/flowfuel/storage/StorageService.java` (interface, se precisar de ajuste)
- `pom.xml` (remover `com.amazonaws:aws-java-sdk-s3`, garantir `software.amazon.awssdk:s3` já presente)
- Novo arquivo de teste: `src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java`
- Configuração de testes (LocalStack/MinIO via Testcontainers, ou mocks do SDK v2)

## Requisitos Técnicos

1. **Criar testes para `S3StorageService` ANTES de remover o SDK v1** (pré-requisito desta task, conforme recomendação do relatório), usando LocalStack/MinIO (Testcontainers) ou mocks do SDK v2 (`S3Client`/`S3Presigner` mockados).
2. Substituir a geração de presigned URL (atualmente via `AmazonS3` do SDK v1) por `S3Presigner` do SDK v2:
   ```java
   S3Presigner presigner = S3Presigner.builder()
           .endpointOverride(URI.create(endpoint))
           .credentialsProvider(credentialsProvider)
           .region(Region.of(region))
           .build();

   GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
   GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
           .signatureDuration(Duration.ofMinutes(15))
           .getObjectRequest(getRequest)
           .build();

   return presigner.presignGetObject(presignRequest).url().toString();
   ```
3. Remover o `catch (Exception e) { /* fallthrough */ }` silencioso — erros de geração de presigned URL devem propagar (ou ser logados explicitamente) em vez de mascarados.
4. Remover a dependência `com.amazonaws:aws-java-sdk-s3` do `pom.xml` e todo o código que inicializa `AmazonS3`/`AWSStaticCredentialsProvider` (SDK v1).
5. Validar que a configuração de credenciais/região permanece consistente usando apenas o SDK v2.

## Passos de Implementação

1. Ler `S3StorageService.java` por completo, mapeando todos os usos do SDK v1 (`AmazonS3`, `AWSStaticCredentialsProvider`, geração de presigned URL).
2. Configurar ambiente de teste local com LocalStack ou MinIO (via Testcontainers) — ou, se preferir um caminho mais rápido, criar testes com mocks de `S3Client`/`S3Presigner` do SDK v2 para os métodos que **não dependem da migração** (upload/download/delete já usam SDK v2).
3. Escrever `S3StorageServiceTest` cobrindo o comportamento **atual** (com os dois SDKs) para upload, download, delete e geração de presigned URL — estabelecendo uma baseline de comportamento antes da migração.
4. Implementar a migração de `getUrl()` para `S3Presigner` (SDK v2), removendo o fallback silencioso.
5. Atualizar `S3StorageServiceTest` para validar a nova implementação de presigned URL — comparar formato/validade da URL gerada com a baseline anterior (mesmo bucket/key/expiração).
6. Remover a dependência do SDK v1 do `pom.xml` e todo código morto relacionado (imports, beans de configuração de credenciais v1).
7. Rodar `mvn dependency:tree` (ou equivalente) para confirmar que `aws-java-sdk-s3` não é mais uma dependência transitiva/direta.
8. Rodar a suíte completa de testes, incluindo fluxos que usam `S3StorageService` (upload de foto de perfil em `UserController`/`UserService`).

## Critérios de Aceitação

- `S3StorageService` usa exclusivamente o AWS SDK v2 (`S3Client` + `S3Presigner`).
- `com.amazonaws:aws-java-sdk-s3` não é mais uma dependência do projeto (`pom.xml` atualizado, artefato de build menor).
- Geração de presigned URL não possui mais `catch` silencioso — erros de configuração são logados/propagados de forma visível.
- `S3StorageServiceTest` existe e cobre upload, download, delete e geração de presigned URL.
- Fluxo de upload/obtenção de foto de perfil (`UserController`/`UserService`) continua funcionando (validado via teste de integração existente).

## Estratégia de Testes

- **Baseline antes da migração:** testes cobrindo o comportamento atual de `S3StorageService` (incluindo presigned URL via SDK v1), usando LocalStack/MinIO ou mocks.
- **Pós-migração:** os mesmos testes adaptados para `S3Presigner` (SDK v2) — validar que URL gerada aponta para o endpoint/bucket/key corretos e tem duração de assinatura configurada (`Duration.ofMinutes(15)` ou valor configurável existente).
- **Teste de erro:** simular falha na geração de presigned URL (ex.: configuração inválida) e verificar que o erro é tratado de forma explícita (logado/propagado), não silenciado.
- Rodar `UserControllerIntegrationTest` (fluxo de upload de foto de perfil) para garantir que a migração não quebra o caminho ponta-a-ponta.

## Riscos

- **Médio risco** — toca configuração de credenciais/infraestrutura de storage, usada em produção para fotos de perfil e potencialmente outros anexos.
- Risco de a URL gerada por `S3Presigner` (SDK v2) ter formato/comportamento sutilmente diferente do SDK v1 (ex.: parâmetros de query da assinatura) — validar compatibilidade com o frontend/Android que consome essas URLs (`CONTEXT_FRONTEND_ANDROID.md`).
- Ambiente de teste (LocalStack/MinIO) pode não replicar 100% o comportamento do S3 real para presigned URLs — validar manualmente em ambiente de staging com bucket real antes do deploy em produção.

## Dependências

**Lacuna de teste identificada no relatório** — não há testes de `S3StorageService` hoje. A criação desses testes é parte **integrante** desta task (não um item separado), e deve ocorrer **antes** da remoção do SDK v1, para validar que o comportamento de presigned URL se mantém.

## Estimativa

2–3 dias (inclui escrita de testes).

## Checklist

- [ ] Analisar código atual
- [ ] Criar testes de baseline para `S3StorageService` (antes da migração)
- [ ] Implementar solução (migração para `S3Presigner` SDK v2)
- [ ] Remover dependência do SDK v1
- [ ] Adicionar/atualizar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
