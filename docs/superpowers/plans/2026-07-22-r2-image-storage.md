# R2 Image Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move image storage (user profile pictures, vehicle photos) out of Postgres (`stored_files` bytea table) into Cloudflare R2, to stop the Neon compute-time quota from being exhausted by image traffic, while keeping the relational database on Neon Postgres unchanged.

**Architecture:** New `R2StorageService` (S3-compatible client via AWS SDK v2) implements the existing `StorageService` interface, replacing `PostgresStorageService` as the active Spring bean. Existing data is copied to R2 with a one-off `CommandLineRunner` before the final cutover. After cutover, `GET /users/{id}/profile-picture` and `GET /vehicles/{id}/photo` return an HTTP 302 redirect to the object's public R2 URL instead of proxying bytes.

**Tech Stack:** Spring Boot 3.5.7 / Java 21, AWS SDK v2 (`software.amazon.awssdk:s3` 2.49.0, S3-compatible client pointed at Cloudflare R2), Thumbnailator (already in use for resize), Cloudflare Wrangler CLI (bucket provisioning), Fly.io secrets (prod config).

**Spec:** [docs/superpowers/specs/2026-07-22-r2-image-storage-design.md](../specs/2026-07-22-r2-image-storage-design.md)

---

## Task 1: Add AWS SDK S3 dependency

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the dependency**

Add this block right after the existing `thumbnailator` dependency (around line 136 of `pom.xml`):

```xml
		<!-- Cliente S3-compatible para Cloudflare R2 (armazenamento de imagens) -->
		<dependency>
			<groupId>software.amazon.awssdk</groupId>
			<artifactId>s3</artifactId>
			<version>2.49.0</version>
		</dependency>
```

- [ ] **Step 2: Verify it resolves and the project still compiles**

Run: `./mvnw -q compile`
Expected: `BUILD SUCCESS`, no dependency resolution errors.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add AWS SDK S3 dependency for Cloudflare R2 client"
```

---

## Task 2: Add R2 and migration configuration properties

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `.env.prod.example`

- [ ] **Step 1: Add properties to `application.properties`**

Add this block at the end of `src/main/resources/application.properties`:

```properties
# Cloudflare R2 (armazenamento de imagens). Ver
# docs/superpowers/specs/2026-07-22-r2-image-storage-design.md
flowfuel.storage.r2.account-id=${CLOUDFLARE_R2_ACCOUNT_ID:}
flowfuel.storage.r2.access-key-id=${CLOUDFLARE_R2_ACCESS_KEY_ID:}
flowfuel.storage.r2.secret-access-key=${CLOUDFLARE_R2_SECRET_ACCESS_KEY:}
flowfuel.storage.r2.bucket=${CLOUDFLARE_R2_BUCKET:}
flowfuel.storage.r2.public-base-url=${CLOUDFLARE_R2_PUBLIC_BASE_URL:}

# Migracao one-off de stored_files (Postgres) para o R2. Ligar so manualmente
# contra prod (flyctl secrets set STORAGE_MIGRATION_ENABLED=true), rodar uma vez,
# depois desligar (flyctl secrets set STORAGE_MIGRATION_ENABLED=false).
flowfuel.storage.migration.enabled=${STORAGE_MIGRATION_ENABLED:false}
```

- [ ] **Step 2: Document the new vars in `.env.prod.example`**

Add this block at the end of `.env.prod.example`:

```
# --- Cloudflare R2 (armazenamento de imagens) ---
CLOUDFLARE_R2_ACCOUNT_ID=
CLOUDFLARE_R2_ACCESS_KEY_ID=
CLOUDFLARE_R2_SECRET_ACCESS_KEY=
CLOUDFLARE_R2_BUCKET=
CLOUDFLARE_R2_PUBLIC_BASE_URL=
```

- [ ] **Step 3: Verify the app still boots with blank R2 config (dev profile)**

Run: `./mvnw -q spring-boot:run -Dspring-boot.run.profiles=dev &` then `sleep 8 && curl -s localhost:8090/actuator/health; kill %1`
Expected: `{"status":"UP"}` — blank R2 properties must not prevent startup (no bean depends on them yet).

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.properties .env.prod.example
git commit -m "feat(storage): add Cloudflare R2 and migration config properties"
```

---

## Task 3: R2Config — S3Client bean

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/storage/R2Config.java`

- [ ] **Step 1: Write the config class**

```java
package com.devappmobile.flowfuel.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
public class R2Config {

    @Value("${flowfuel.storage.r2.account-id}")
    private String accountId;

    @Value("${flowfuel.storage.r2.access-key-id}")
    private String accessKeyId;

    @Value("${flowfuel.storage.r2.secret-access-key}")
    private String secretAccessKey;

    @Bean
    public S3Client r2S3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create("https://" + accountId + ".r2.cloudflarestorage.com"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .region(Region.of("auto"))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
```

- [ ] **Step 2: Verify the app still boots (bean must build even with blank credentials in dev/test)**

Run: `./mvnw -q spring-boot:run -Dspring-boot.run.profiles=dev &` then `sleep 8 && curl -s localhost:8090/actuator/health; kill %1`
Expected: `{"status":"UP"}` — `S3Client.builder().build()` does not validate credentials or connect at construction time, so blank strings are fine here.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/storage/R2Config.java
git commit -m "feat(storage): add S3Client bean configured for Cloudflare R2"
```

---

## Task 4: R2StorageService

This keeps the *existing* `StorageService` interface unchanged (still has `download` returning bytes) so it can coexist with `PostgresStorageService` without touching any controller yet. `PostgresStorageService` is marked `@Primary` in this same task so it remains the active bean — introducing a second `@Service` of the same interface without a primary would break every Spring context test immediately.

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/storage/R2StorageService.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/storage/PostgresStorageService.java`
- Test: `src/test/java/com/devappmobile/flowfuel/storage/R2StorageServiceTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.devappmobile.flowfuel.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class R2StorageServiceTest {

    @Mock private S3Client s3Client;

    private R2StorageService service;

    @BeforeEach
    void setUp() {
        service = new R2StorageService(s3Client, "test-bucket");
    }

    private byte[] pngOf(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void upload_redimensionaImagemGrandeEEnviaParaR2() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "foto.png", "image/png", pngOf(2000, 1000));

        service.upload(file, "users/1/photo.png");

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

        assertThat(requestCaptor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo("users/1/photo.png");
        assertThat(requestCaptor.getValue().contentType()).isEqualTo("image/jpeg");

        BufferedImage resized = ImageIO.read(bodyCaptor.getValue().contentStreamProvider().newStream());
        assertThat(resized.getWidth()).isLessThanOrEqualTo(512);
        assertThat(resized.getHeight()).isLessThanOrEqualTo(512);
    }

    @Test
    void upload_comImagemCorrompida_lancaBusinessRuleENaoChamaS3() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "foto.jpg", "image/jpeg", "isso nao e uma imagem".getBytes());

        assertThatThrownBy(() -> service.upload(file, "users/1/photo.jpg"))
                .isInstanceOf(com.devappmobile.flowfuel.exception.BusinessRuleException.class);
        verifyNoInteractions(s3Client);
    }

    @Test
    void delete_chamaDeleteObjectComBucketEKey() {
        service.delete("users/1/photo.png");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(captor.getValue().key()).isEqualTo("users/1/photo.png");
    }

    @Test
    void download_retornaBytesEContentTypeDoS3() {
        byte[] data = {1, 2, 3};
        GetObjectResponse response = GetObjectResponse.builder().contentType("image/jpeg").build();
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(response, data));

        StorageService.StorageObject result = service.download("users/1/photo.png");

        assertThat(result.data()).isEqualTo(data);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=R2StorageServiceTest`
Expected: FAIL — `R2StorageService` does not exist yet.

- [ ] **Step 3: Write `R2StorageService`**

```java
package com.devappmobile.flowfuel.storage;

import com.devappmobile.flowfuel.exception.BusinessRuleException;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class R2StorageService implements StorageService {

    private static final int MAX_DIMENSION = 512;
    private static final String OUTPUT_CONTENT_TYPE = "image/jpeg";

    private final S3Client s3Client;
    private final String bucket;

    public R2StorageService(S3Client s3Client, @Value("${flowfuel.storage.r2.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public String upload(MultipartFile file, String key) {
        byte[] resized = resize(file);
        putObject(key, resized, OUTPUT_CONTENT_TYPE);
        return key;
    }

    /** Usado tambem pela migracao one-off (ImagesToR2MigrationRunner) para copiar bytes ja redimensionados sem reprocessar. */
    void putObject(String key, byte[] data, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(data));
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public StorageObject download(String key) {
        ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return new StorageObject(object.asByteArray(), object.response().contentType());
    }

    private byte[] resize(MultipartFile file) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(file.getInputStream())
                    .size(MAX_DIMENSION, MAX_DIMENSION)
                    .outputFormat("jpg")
                    .outputQuality(0.85)
                    .toOutputStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessRuleException("Arquivo de imagem inválido ou corrompido");
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=R2StorageServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Mark `PostgresStorageService` `@Primary` so it stays the active bean**

In `src/main/java/com/devappmobile/flowfuel/storage/PostgresStorageService.java`, add the import and annotation:

```java
import org.springframework.context.annotation.Primary;
```

```java
@Service
@Primary
@RequiredArgsConstructor
public class PostgresStorageService implements StorageService {
```

- [ ] **Step 6: Run the full suite to confirm nothing else broke**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`. All existing photo/profile-picture integration tests still resolve `PostgresStorageService` (via `@Primary`) exactly as before — behavior is unchanged, `R2StorageService` exists as an unused bean.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/storage/R2StorageService.java \
        src/main/java/com/devappmobile/flowfuel/storage/PostgresStorageService.java \
        src/test/java/com/devappmobile/flowfuel/storage/R2StorageServiceTest.java
git commit -m "feat(storage): add R2StorageService alongside Postgres (not yet active)"
```

---

## Task 5: One-off migration runner (Postgres → R2)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/storage/ImagesToR2MigrationRunner.java`
- Test: `src/test/java/com/devappmobile/flowfuel/storage/ImagesToR2MigrationRunnerTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.devappmobile.flowfuel.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImagesToR2MigrationRunnerTest {

    @Mock private StoredFileRepository storedFileRepository;
    @Mock private R2StorageService r2StorageService;

    @InjectMocks private ImagesToR2MigrationRunner runner;

    @Test
    void migrate_copiaTodasAsLinhasParaR2() {
        StoredFile a = new StoredFile("k1", "image/jpeg", new byte[]{1});
        StoredFile b = new StoredFile("k2", "image/jpeg", new byte[]{2});
        when(storedFileRepository.findAll()).thenReturn(List.of(a, b));

        ImagesToR2MigrationRunner.MigrationSummary summary = runner.migrate();

        verify(r2StorageService).putObject("k1", a.getData(), "image/jpeg");
        verify(r2StorageService).putObject("k2", b.getData(), "image/jpeg");
        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.success()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(0);
    }

    @Test
    void migrate_linhaComFalha_naoAbortaAsDemaisEContabilizaFalha() {
        StoredFile a = new StoredFile("k1", "image/jpeg", new byte[]{1});
        StoredFile b = new StoredFile("k2", "image/jpeg", new byte[]{2});
        when(storedFileRepository.findAll()).thenReturn(List.of(a, b));
        doThrow(new RuntimeException("falha de rede"))
                .when(r2StorageService).putObject(eq("k1"), any(), any());

        ImagesToR2MigrationRunner.MigrationSummary summary = runner.migrate();

        verify(r2StorageService).putObject("k2", b.getData(), "image/jpeg");
        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.success()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
    }

    @Test
    void migrate_semLinhas_retornaSummaryZerado() {
        when(storedFileRepository.findAll()).thenReturn(List.of());

        ImagesToR2MigrationRunner.MigrationSummary summary = runner.migrate();

        assertThat(summary.total()).isZero();
        assertThat(summary.success()).isZero();
        assertThat(summary.failed()).isZero();
        verifyNoInteractions(r2StorageService);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=ImagesToR2MigrationRunnerTest`
Expected: FAIL — `ImagesToR2MigrationRunner` does not exist yet.

- [ ] **Step 3: Write `ImagesToR2MigrationRunner`**

```java
package com.devappmobile.flowfuel.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Migracao one-off dos arquivos existentes em stored_files (Postgres) para o R2, preservando
 * a mesma key. So roda quando flowfuel.storage.migration.enabled=true (ligar manualmente
 * contra prod via fly secrets, rodar uma vez, desligar). Ver
 * docs/superpowers/specs/2026-07-22-r2-image-storage-design.md
 */
@Component
@ConditionalOnProperty(name = "flowfuel.storage.migration.enabled", havingValue = "true")
public class ImagesToR2MigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ImagesToR2MigrationRunner.class);

    private final StoredFileRepository storedFileRepository;
    private final R2StorageService r2StorageService;

    public ImagesToR2MigrationRunner(StoredFileRepository storedFileRepository, R2StorageService r2StorageService) {
        this.storedFileRepository = storedFileRepository;
        this.r2StorageService = r2StorageService;
    }

    @Override
    public void run(String... args) {
        MigrationSummary summary = migrate();
        log.info("[ImagesToR2MigrationRunner] total={} sucesso={} falha={}",
                summary.total(), summary.success(), summary.failed());
    }

    MigrationSummary migrate() {
        List<StoredFile> files = storedFileRepository.findAll();
        int success = 0;
        int failed = 0;
        for (StoredFile file : files) {
            try {
                r2StorageService.putObject(file.getKey(), file.getData(), file.getContentType());
                success++;
            } catch (Exception e) {
                failed++;
                log.error("[ImagesToR2MigrationRunner] falha ao migrar key={}", file.getKey(), e);
            }
        }
        return new MigrationSummary(files.size(), success, failed);
    }

    record MigrationSummary(int total, int success, int failed) {}
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=ImagesToR2MigrationRunnerTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full suite**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`. `flowfuel.storage.migration.enabled` defaults to `false`, so this bean is never created in dev/test/prod until explicitly turned on — no risk of it running automatically.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/storage/ImagesToR2MigrationRunner.java \
        src/test/java/com/devappmobile/flowfuel/storage/ImagesToR2MigrationRunnerTest.java
git commit -m "feat(storage): add one-off migration runner from Postgres to R2"
```

---

## Task 6: Provision the Cloudflare R2 bucket and credentials

This is an infra-provisioning task, not code. Run it once, from a terminal with access to the Cloudflare account (the wrangler OAuth login opens a browser, same flow used earlier for `neonctl`).

- [ ] **Step 1: Install and authenticate Wrangler**

```bash
npm install -g wrangler
wrangler login
```

Follow the printed URL, authorize in the browser.

- [ ] **Step 2: Create the bucket**

```bash
wrangler r2 bucket create flowfuel-images
```

- [ ] **Step 3: Enable the public `*.r2.dev` URL for the bucket**

```bash
wrangler r2 bucket dev-url enable flowfuel-images
```

Expected output includes a public URL like `https://pub-<hash>.r2.dev`. Save it — this is `CLOUDFLARE_R2_PUBLIC_BASE_URL`.

> Custom domain (`images.flowfuel.app`) is out of scope here — `flowfuel.app` isn't on Cloudflare DNS yet (per the design doc). Revisit `wrangler r2 bucket domain add` once the domain is migrated.

- [ ] **Step 4: Create the S3-compatible API token (manual — not exposed by Wrangler)**

Wrangler manages buckets/objects through Cloudflare's own auth, not S3-style credentials — the Access Key ID / Secret Access Key pair used by the AWS SDK has to be created in the dashboard:

1. Cloudflare dashboard → **R2** → **Manage R2 API Tokens** → **Create API Token**.
2. Permission: **Object Read & Write**, scoped to the `flowfuel-images` bucket.
3. Copy the **Access Key ID**, **Secret Access Key**, and the **Account ID** shown on the same page (also visible in the R2 overview page URL / sidebar).

- [ ] **Step 5: Set the secrets on Fly.io**

```bash
flyctl secrets set \
  CLOUDFLARE_R2_ACCOUNT_ID="<account-id>" \
  CLOUDFLARE_R2_ACCESS_KEY_ID="<access-key-id>" \
  CLOUDFLARE_R2_SECRET_ACCESS_KEY="<secret-access-key>" \
  CLOUDFLARE_R2_BUCKET="flowfuel-images" \
  CLOUDFLARE_R2_PUBLIC_BASE_URL="https://pub-<hash>.r2.dev"
```

This alone doesn't change any behavior yet — `PostgresStorageService` is still `@Primary`, `R2StorageService` is just now able to reach the real bucket.

- [ ] **Step 6: Deploy so the new secrets take effect and confirm the app is still healthy**

```bash
flyctl deploy
flyctl status
curl -s https://flowfuel-api.fly.dev/actuator/health
```

Expected: `{"status":"UP"}`.

(No commit for this task — it's account/infra setup, not a code change.)

---

## Task 7: Run the migration against production

- [ ] **Step 1: Turn the migration on and deploy**

```bash
flyctl secrets set STORAGE_MIGRATION_ENABLED=true
```

This triggers a redeploy (Fly restarts the machine on secret change), which runs `ImagesToR2MigrationRunner` once at boot.

- [ ] **Step 2: Check the logs for the summary line**

```bash
flyctl logs | grep ImagesToR2MigrationRunner
```

Expected: a line like `[ImagesToR2MigrationRunner] total=N sucesso=N falha=0`.

**If `falha` is greater than 0:** do not proceed to Task 8. Check the logged error per failed `key`, fix the underlying issue (e.g. missing bucket permission), and re-run this task — `putObject` is an overwrite, so re-running is safe and idempotent.

- [ ] **Step 3: Turn the migration flag back off**

```bash
flyctl secrets set STORAGE_MIGRATION_ENABLED=false
```

This prevents the runner from re-executing on every future boot. `stored_files` in Postgres is left untouched (read-only migration) — it stays as a backup per the rollout plan.

(No commit — this is a production operation, not a code change.)

---

## Task 8: Cutover — interface change, redirect responses, active bean swap

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/storage/StorageService.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/storage/R2StorageService.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/storage/PostgresStorageService.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserProfileService.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserController.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleController.java`
- Test: `src/test/java/com/devappmobile/flowfuel/storage/R2StorageServiceTest.java`
- Test: `src/test/java/com/devappmobile/flowfuel/storage/PostgresStorageServiceTest.java`
- Test: `src/test/java/com/devappmobile/flowfuel/user/UserProfileServiceTest.java`
- Test: `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java`
- Test: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java`
- Test: `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleControllerIntegrationTest.java`

### Step 1: Change the `StorageService` interface

Replace the whole file `src/main/java/com/devappmobile/flowfuel/storage/StorageService.java`:

```java
package com.devappmobile.flowfuel.storage;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

    String upload(MultipartFile file, String key);

    void delete(String key);

    String publicUrl(String key);
}
```

- [ ] Done.

### Step 2: Update `R2StorageService` — replace `download` with `publicUrl`

In `src/main/java/com/devappmobile/flowfuel/storage/R2StorageService.java`:

Remove the `download` method and its now-unused imports (`ResponseBytes`, `GetObjectRequest`, `GetObjectResponse`). Add a `publicBaseUrl` field, thread it through the constructor, and implement `publicUrl`:

```java
    private final S3Client s3Client;
    private final String bucket;
    private final String publicBaseUrl;

    public R2StorageService(S3Client s3Client,
            @Value("${flowfuel.storage.r2.bucket}") String bucket,
            @Value("${flowfuel.storage.r2.public-base-url}") String publicBaseUrl) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl;
    }
```

```java
    @Override
    public String publicUrl(String key) {
        return publicBaseUrl + "/" + key;
    }
```

- [ ] Done.

### Step 3: Update `R2StorageServiceTest`

In `src/test/java/com/devappmobile/flowfuel/storage/R2StorageServiceTest.java`:
- Update `setUp()`: `service = new R2StorageService(s3Client, "test-bucket", "https://pub-test.r2.dev");`
- Delete the `download_retornaBytesEContentTypeDoS3` test and its now-unused imports (`ResponseBytes`, `GetObjectRequest`, `GetObjectResponse`).
- Add:

```java
    @Test
    void publicUrl_concatenaBaseUrlEKey() {
        assertThat(service.publicUrl("users/1/photo.png"))
                .isEqualTo("https://pub-test.r2.dev/users/1/photo.png");
    }
```

- [ ] **Run:** `./mvnw -q test -Dtest=R2StorageServiceTest` — expect PASS (4 tests: resize/putObject, corrupt image, delete, publicUrl).

### Step 4: Update `PostgresStorageService` — drop `@Primary`, drop `download`, implement `publicUrl` as unsupported

Replace `src/main/java/com/devappmobile/flowfuel/storage/PostgresStorageService.java` with:

```java
package com.devappmobile.flowfuel.storage;

import com.devappmobile.flowfuel.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Mantida apenas como historico/rollback ate a remocao definitiva (fora de escopo do design
 * de 2026-07-22 — ver "Fora de escopo"). Nao e mais o bean ativo: R2StorageService assumiu
 * esse papel apos a migracao dos dados existentes.
 */
@Service
@RequiredArgsConstructor
public class PostgresStorageService implements StorageService {

    private static final int MAX_DIMENSION = 512;
    private static final String OUTPUT_CONTENT_TYPE = "image/jpeg";

    private final StoredFileRepository storedFileRepository;

    @Override
    public String upload(MultipartFile file, String key) {
        byte[] resized = resize(file);
        storedFileRepository.save(new StoredFile(key, OUTPUT_CONTENT_TYPE, resized));
        return key;
    }

    @Override
    public void delete(String key) {
        storedFileRepository.deleteById(key);
    }

    @Override
    public String publicUrl(String key) {
        throw new UnsupportedOperationException(
                "PostgresStorageService nao suporta publicUrl; imagens sao servidas via R2StorageService");
    }

    private byte[] resize(MultipartFile file) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(file.getInputStream())
                    .size(MAX_DIMENSION, MAX_DIMENSION)
                    .outputFormat("jpg")
                    .outputQuality(0.85)
                    .toOutputStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessRuleException("Arquivo de imagem inválido ou corrompido");
        }
    }
}
```

- [ ] Done.

### Step 5: Update `PostgresStorageServiceTest`

In `src/test/java/com/devappmobile/flowfuel/storage/PostgresStorageServiceTest.java`, delete these two tests (they exercise the removed `download` method) and their now-unused `ResourceNotFoundException`/`StorageService` imports if no longer referenced elsewhere in the file:

- `download_keyExistente_retornaDadosEContentType`
- `download_keyInexistente_lancaResourceNotFound`

Add in their place:

```java
    @Test
    void publicUrl_lancaUnsupportedOperation() {
        assertThatThrownBy(() -> service.publicUrl("users/1/photo.png"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
```

- [ ] **Run:** `./mvnw -q test -Dtest=PostgresStorageServiceTest` — expect PASS.

### Step 6: `UserProfileService` — replace `getProfilePictureKey` with `getProfilePictureUrl`

In `src/main/java/com/devappmobile/flowfuel/user/UserProfileService.java`, replace:

```java
    public String getProfilePictureKey(Long userId) {
        return findUserOrThrow(userId).getProfilePicture();
    }
```

with:

```java
    public String getProfilePictureUrl(Long userId) {
        String key = findUserOrThrow(userId).getProfilePicture();
        return key != null ? storageService.publicUrl(key) : null;
    }
```

- [ ] Done.

### Step 7: `UserProfileServiceTest` — add coverage for `getProfilePictureUrl`

Add to `src/test/java/com/devappmobile/flowfuel/user/UserProfileServiceTest.java`:

```java
    // --- getProfilePictureUrl ---

    @Test
    void getProfilePictureUrl_comFoto_retornaUrlPublica() {
        existingUser.setProfilePicture("profile_pictures/1_foto.jpg");
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(storageService.publicUrl("profile_pictures/1_foto.jpg"))
                .thenReturn("https://pub-test.r2.dev/profile_pictures/1_foto.jpg");

        String url = userProfileService.getProfilePictureUrl(1L);

        assertThat(url).isEqualTo("https://pub-test.r2.dev/profile_pictures/1_foto.jpg");
    }

    @Test
    void getProfilePictureUrl_semFoto_retornaNull() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));

        String url = userProfileService.getProfilePictureUrl(1L);

        assertThat(url).isNull();
        verify(storageService, never()).publicUrl(any());
    }
```

- [ ] **Run:** `./mvnw -q test -Dtest=UserProfileServiceTest` — expect PASS.

### Step 8: `UserController` — redirect instead of proxying bytes

In `src/main/java/com/devappmobile/flowfuel/user/UserController.java`:

Remove the now-unused field (line 24):

```java
    private final com.devappmobile.flowfuel.storage.StorageService storageService;
```

Replace `getProfilePicture` (lines 98-108):

```java
    @GetMapping("/{userId}/profile-picture")
    public ResponseEntity<Void> getProfilePicture(@PathVariable Long userId,
            @AuthenticationPrincipal User authUser) {
        ensureSelf(authUser, userId);
        String url = userProfileService.getProfilePictureUrl(userId);
        if (url == null) return ResponseEntity.noContent().build();
        return ResponseEntity.status(HttpStatus.FOUND).location(java.net.URI.create(url)).build();
    }
```

- [ ] **Run:** `./mvnw -q compile` — expect `BUILD SUCCESS` (confirms the unused-field removal didn't break other usages; `storageService` isn't referenced anywhere else in this class).

### Step 9: Add a redirect test to `UserControllerIntegrationTest`

Add to `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java`. This test needs the real `StorageService` mocked (once `R2StorageService` becomes `@Primary` in Step 12, this test would otherwise try to hit real Cloudflare R2 over the network):

Add the import:

```java
import org.springframework.boot.test.mock.mockito.MockBean;
import com.devappmobile.flowfuel.storage.StorageService;
```

Add the mock field next to the other `@Autowired` fields:

```java
    @MockBean private StorageService storageService;
```

Add the test:

```java
    @Test
    void getProfilePicture_aposUpload_retorna302ComLocation() throws Exception {
        MvcResult registerResult = registrar("foto-perfil@test.com", "senha123");
        long userId = objectMapper.readTree(registerResult.getResponse().getContentAsString()).get("id").asLong();
        String token = obterToken("foto-perfil@test.com", "senha123");

        when(storageService.upload(any(), any())).thenReturn("profile_pictures/" + userId + "_foto.png");
        when(storageService.publicUrl(any()))
                .thenAnswer(inv -> "https://pub-test.r2.dev/" + inv.getArgument(0, String.class));

        mockMvc.perform(multipart("/api/v1/auth/{id}/upload-profile-picture", userId)
                .file(new org.springframework.mock.web.MockMultipartFile(
                        "file", "foto.png", "image/png", new byte[]{1, 2, 3}))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/{id}/profile-picture", userId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.startsWith("https://pub-test.r2.dev/profile_pictures/")));
    }

    @Test
    void getProfilePicture_semFotoUpada_retorna204() throws Exception {
        MvcResult registerResult = registrar("foto-perfil204@test.com", "senha123");
        long userId = objectMapper.readTree(registerResult.getResponse().getContentAsString()).get("id").asLong();
        String token = obterToken("foto-perfil204@test.com", "senha123");

        mockMvc.perform(get("/api/v1/auth/{id}/profile-picture", userId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }
```

Add this static import at the top of the file (next to the existing `static org.mockito.Mockito` import if present, otherwise add fresh):

```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
```

- [ ] **Run:** `./mvnw -q test -Dtest=UserControllerIntegrationTest` — expect PASS (all existing tests plus the two new ones; `@MockBean` overrides whichever `StorageService` bean would otherwise be resolved, so this test is unaffected by which implementation is `@Primary`).

### Step 10: `VehicleService.getPhoto` — redirect instead of proxying bytes

In `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java`, replace `getPhoto` (lines 125-136):

```java
    public ResponseEntity<Void> getPhoto(User user, Long id) {
        Vehicle vehicle = findOwned(user, id);
        String key = vehicle.getPhoto();
        if (key == null) {
            return ResponseEntity.noContent().build();
        }

        String url = storageService.publicUrl(key);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
```

Add imports at the top of the file:

```java
import org.springframework.http.HttpStatus;
import java.net.URI;
```

Remove the now-unused `StorageService.StorageObject` reference — there is none left in this file after this change (the only other `StorageService` calls are `delete`/`upload`, both untouched).

- [ ] Done.

### Step 11: `VehicleController.getPhoto` — update return type

In `src/main/java/com/devappmobile/flowfuel/vehicle/VehicleController.java`, change:

```java
    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> getPhoto(
```

to:

```java
    @GetMapping("/{id}/photo")
    public ResponseEntity<Void> getPhoto(
```

- [ ] **Run:** `./mvnw -q compile` — expect `BUILD SUCCESS`.

### Step 12: Update `VehicleServiceTest`

In `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java`, replace the three `getPhoto_*` tests:

```java
    @Test
    void getPhoto_semFoto_retorna204() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        org.springframework.http.ResponseEntity<Void> response = vehicleService.getPhoto(owner, 10L);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.NO_CONTENT);
        verify(storageService, never()).publicUrl(any());
    }

    @Test
    void getPhoto_comFoto_retorna302ComLocation() {
        vehicle.setPhoto("vehicle_photos/10_foto.jpg");
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(storageService.publicUrl("vehicle_photos/10_foto.jpg"))
                .thenReturn("https://pub-test.r2.dev/vehicle_photos/10_foto.jpg");

        org.springframework.http.ResponseEntity<Void> response = vehicleService.getPhoto(owner, 10L);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .isEqualTo(java.net.URI.create("https://pub-test.r2.dev/vehicle_photos/10_foto.jpg"));
    }

    @Test
    void getPhoto_donoDiferente_lancaForbidden() {
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        doThrow(new ForbiddenOperationException("Veículo não pertence ao usuário"))
                .when(authorizationHelper).ensureOwnsVehicle(otherUser, vehicle);

        assertThatThrownBy(() -> vehicleService.getPhoto(otherUser, 10L))
                .isInstanceOf(ForbiddenOperationException.class);
    }
```

(The `getPhoto_donoDiferente_lancaForbidden` and `getPhoto_veiculoInexistente_lancaResourceNotFound` tests are unchanged — only listed here for placement; leave `getPhoto_veiculoInexistente_lancaResourceNotFound` as-is right after.)

Remove the now-unused `StorageService.StorageObject` import/reference if no longer used elsewhere in the file (check `uploadPhoto` tests — they only call `storageService.upload(...)`, no `StorageObject` usage remains).

- [ ] **Run:** `./mvnw -q test -Dtest=VehicleServiceTest` — expect PASS.

### Step 13: Update `VehicleControllerIntegrationTest`

In `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleControllerIntegrationTest.java`:

Add imports:

```java
import org.springframework.boot.test.mock.mockito.MockBean;
import com.devappmobile.flowfuel.storage.StorageService;
```

Add the mock field next to the other `@Autowired` fields:

```java
    @MockBean private StorageService storageService;
```

At the top of `@BeforeEach limparBanco()`, stub the two methods every photo test relies on (upload returning the key it was given, publicUrl echoing it back as a fake CDN URL):

```java
    @BeforeEach
    void limparBanco() {
        refuelRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();
        reset(storageService);
        when(storageService.upload(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(storageService.publicUrl(any()))
                .thenAnswer(inv -> "https://pub-test.r2.dev/" + inv.getArgument(0, String.class));
    }
```

Add these static imports:

```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
```

Replace `getPhoto_aposUpload_retorna200ComBytes` (it asserted a body of raw bytes, which no longer exists):

```java
    @Test
    void getPhoto_aposUpload_retorna302ComLocation() throws Exception {
        String token = obterToken("foto-get200@test.com");
        long vehicleId = criarVeiculo(token);

        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", imagemJpegValida());
        mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.startsWith("https://pub-test.r2.dev/vehicle_photos/" + vehicleId)));
    }
```

The other tests (`getPhoto_semFotoUpada_retorna204`, `getPhoto_donoDiferente_retorna403`, `getPhoto_veiculoInexistente_retorna404`, `uploadPhoto_*`, `deletePhoto_*`) don't need changes — they either don't reach `getPhoto`'s success path or don't assert on the body/status touched by this change; they now run against the mocked `storageService` instead of a real `PostgresStorageService`/H2 round-trip, which is equivalent for their purposes.

- [ ] **Run:** `./mvnw -q test -Dtest=VehicleControllerIntegrationTest` — expect PASS (all tests, including the replaced one).

### Step 14: Flip the active bean — `R2StorageService` becomes `@Primary`

In `src/main/java/com/devappmobile/flowfuel/storage/R2StorageService.java`, add the import and annotation:

```java
import org.springframework.context.annotation.Primary;
```

```java
@Service
@Primary
public class R2StorageService implements StorageService {
```

In `src/main/java/com/devappmobile/flowfuel/storage/PostgresStorageService.java`, remove `@Primary` (it should no longer have the annotation — plain `@Service` only).

- [ ] **Run the full suite:** `./mvnw -q test`
Expected: `BUILD SUCCESS`. Every `@SpringBootTest` class that touches photo/profile-picture endpoints now has `StorageService` mocked via `@MockBean`, so the bean flip doesn't change their behavior. Unit tests (`VehicleServiceTest`, `UserProfileServiceTest`) already mock `StorageService` directly and are unaffected by which implementation is `@Primary`.

- [ ] **Step 15: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/storage/StorageService.java \
        src/main/java/com/devappmobile/flowfuel/storage/R2StorageService.java \
        src/main/java/com/devappmobile/flowfuel/storage/PostgresStorageService.java \
        src/main/java/com/devappmobile/flowfuel/user/UserProfileService.java \
        src/main/java/com/devappmobile/flowfuel/user/UserController.java \
        src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java \
        src/main/java/com/devappmobile/flowfuel/vehicle/VehicleController.java \
        src/test/java/com/devappmobile/flowfuel/storage/R2StorageServiceTest.java \
        src/test/java/com/devappmobile/flowfuel/storage/PostgresStorageServiceTest.java \
        src/test/java/com/devappmobile/flowfuel/user/UserProfileServiceTest.java \
        src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java \
        src/test/java/com/devappmobile/flowfuel/vehicle/VehicleServiceTest.java \
        src/test/java/com/devappmobile/flowfuel/vehicle/VehicleControllerIntegrationTest.java
git commit -m "feat(storage): cut over to R2 — images served via 302 redirect to public URL"
```

---

## Task 9: Deploy the cutover and verify in production

- [ ] **Step 1: Deploy**

```bash
flyctl deploy
```

- [ ] **Step 2: Smoke test — upload and fetch a real profile picture**

```bash
# Substitua <token> por um access token valido (login via POST /api/v1/auth/login)
curl -s -X POST https://flowfuel-api.fly.dev/api/v1/auth/<userId>/upload-profile-picture \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/test-image.jpg"

curl -sI https://flowfuel-api.fly.dev/api/v1/auth/<userId>/profile-picture \
  -H "Authorization: Bearer <token>"
```

Expected: second call returns `HTTP/2 302` with a `location:` header pointing at `https://pub-<hash>.r2.dev/...`; opening that URL directly in a browser shows the uploaded (resized) image.

- [ ] **Step 3: Smoke test — confirm a photo migrated in Task 7 still loads**

Pick a `key` you saw in the Task 7 migration logs (`stored_files` had at least one row before migration), and check:

```bash
curl -sI "https://pub-<hash>.r2.dev/<key-from-migration-log>"
```

Expected: `HTTP/2 200`.

**If this 404s:** the migration in Task 7 didn't actually reach this key (check the per-row error log around that key) — do not delete `stored_files` in a future cleanup until this is resolved.

- [ ] **Step 4: Watch Sentry / `flyctl logs` for a day before considering `PostgresStorageService`/`stored_files` safe to remove** (that removal is a separate, future, out-of-scope task per the design doc).

(No commit — this is verification, not a code change.)

---

## Task 10: Update deploy documentation

**Files:**
- Modify: `docs/deploy.md`

- [ ] **Step 1: Add a new section documenting the R2 setup, right after the existing "### 7. Redis para rate-limiting" section**

```markdown
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
```

- [ ] **Step 2: Add a note to "Pontos de atenção / pendências"**

Read the current contents of that section first (`docs/deploy.md`, look for `## Pontos de atenção / pendências`) and append one bullet:

```markdown
- **Imagens no R2, não mais no Postgres**: `PostgresStorageService`/tabela `stored_files` ficam como backup temporário pós-migração (ver design de 2026-07-22); remover numa entrega futura depois de confirmar estabilidade em produção.
```

- [ ] **Step 3: Commit**

```bash
git add docs/deploy.md
git commit -m "docs(deploy): document Cloudflare R2 provisioning and secrets"
```

---

## Self-Review Notes

- **Spec coverage:** architecture (Tasks 3-4, 8), migration script (Task 5, 7), rollout deploy 1/deploy 2 split (Task 4 = deploy 1, Task 8-9 = deploy 2), error handling (BusinessRuleException on resize failure — Task 4; per-row migration failure — Task 5; UnsupportedOperationException on retired Postgres publicUrl — Task 8), tests (unit + integration updated throughout Task 8), docs update (Task 10) — all covered.
- **Out of scope confirmed unchanged:** no task touches Neon/Postgres connection config, no task deletes `PostgresStorageService`/`stored_files`, no task configures a custom domain.
- **Type consistency check:** `StorageService.publicUrl(String)` used identically in `R2StorageService`, `PostgresStorageService`, `UserProfileService`, `VehicleService` (Task 8). `R2StorageService.putObject(String key, byte[] data, String contentType)` package-private signature matches its two callers: `upload()` (Task 4) and `ImagesToR2MigrationRunner.migrate()` (Task 5) — both in the same `storage` package.
