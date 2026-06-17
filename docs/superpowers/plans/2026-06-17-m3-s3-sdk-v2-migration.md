# M3 — Consolidar para AWS SDK v2 (S3Presigner), remover SDK v1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the AWS SDK v1 dependency (`com.amazonaws:aws-java-sdk-s3`) from `S3StorageService`, replacing its only use (presigned URL generation) with AWS SDK v2's `S3Presigner`, backed by a new test suite that did not exist before.

**Architecture:** `S3StorageService` currently builds two AWS clients in `@PostConstruct`: `S3Client` (v2, used for upload/delete/download) and `AmazonS3` (v1, used only by `getUrl()`). We add unit tests against the *current* behavior first (baseline), using Mockito mocks for `S3Client`/`AmazonS3` injected via `ReflectionTestUtils` (the fields are private and set in `@PostConstruct`, not constructor-injected, so reflection is the lowest-friction way to test this class without restructuring it). Then we swap the v1 client for `S3Presigner`, remove the silent `catch` fallback (replacing it with explicit logging + rethrow), update the tests for the new implementation, and finally drop the SDK v1 Maven dependency and confirm nothing else references it.

**Tech Stack:** Spring Boot, AWS SDK v2 (`software.amazon.awssdk:s3`), JUnit 5, Mockito, AssertJ, `org.springframework.test.util.ReflectionTestUtils`, SLF4J.

---

## File Structure

- Modify: `src/main/java/com/devappmobile/flowfuel/storage/S3StorageService.java` — remove SDK v1 client/imports, add `S3Presigner` field + initialization, rewrite `getUrl()`.
- Modify: `pom.xml` — remove the `com.amazonaws:aws-java-sdk-s3` dependency.
- Create: `src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java` — unit tests for `upload`, `delete`, `download`, `getUrl` (baseline with SDK v1, then updated for SDK v2).
- No change needed to `StorageService.java` (interface is already SDK-agnostic).

---

### Task 1: Baseline tests for current behavior (upload/delete/download)

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java`

- [ ] **Step 1: Write failing tests for `upload`, `delete`, `download`**

```java
package com.devappmobile.flowfuel.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    private S3Client s3Client;
    private S3StorageService service;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        service = new S3StorageService();
        ReflectionTestUtils.setField(service, "s3", s3Client);
        ReflectionTestUtils.setField(service, "bucket", "test-bucket");
        ReflectionTestUtils.setField(service, "region", "us-west-002");
        ReflectionTestUtils.setField(service, "endpoint", "https://s3.test-endpoint.com");
    }

    @Test
    void upload_putsObjectAndReturnsKey() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", "fake-bytes".getBytes());
        when(s3Client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String key = service.upload(file, "users/1/photo.png");

        assertThat(key).isEqualTo("users/1/photo.png");
        verify(s3Client).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void delete_deletesObjectByKey() {
        service.delete("users/1/photo.png");

        verify(s3Client).deleteObject(any(java.util.function.Consumer.class));
    }

    @Test
    void download_returnsBytesAndContentType() {
        byte[] content = "image-bytes".getBytes();
        GetObjectResponse response = GetObjectResponse.builder().contentType("image/png").build();
        ResponseInputStream<GetObjectResponse> responseStream =
                new ResponseInputStream<>(response, new ByteArrayInputStream(content));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

        StorageService.StorageObject result = service.download("users/1/photo.png");

        assertThat(result.data()).isEqualTo(content);
        assertThat(result.contentType()).isEqualTo("image/png");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail or pass against current code**

Run: `mvn -q -Dtest=S3StorageServiceTest test`
Expected: compiles and PASSES for `upload`, `delete`, `download` (these already use SDK v2, so this just establishes the baseline harness — not a red/green TDD cycle for these three).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java
git commit -m "test(storage): add baseline tests for S3StorageService upload/delete/download"
```

---

### Task 2: Baseline test for current `getUrl()` (SDK v1 presigned URL path)

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java`

- [ ] **Step 1: Add failing/baseline tests for `getUrl()` covering the legacy SDK v1 client and the fallback paths**

```java
    @Test
    void getUrl_withLegacyClient_returnsPresignedUrl() throws Exception {
        com.amazonaws.services.s3.AmazonS3 legacyClient = mock(com.amazonaws.services.s3.AmazonS3.class);
        ReflectionTestUtils.setField(service, "legacyS3Client", legacyClient);
        java.net.URL presigned = new java.net.URL("https://s3.test-endpoint.com/test-bucket/users/1/photo.png?X-Amz-Signature=abc");
        when(legacyClient.generatePresignedUrl(any(com.amazonaws.services.s3.model.GeneratePresignedUrlRequest.class)))
                .thenReturn(presigned);

        String url = service.getUrl("users/1/photo.png");

        assertThat(url).isEqualTo(presigned.toString());
    }

    @Test
    void getUrl_whenLegacyClientThrows_fallsBackToEndpointUrl() {
        com.amazonaws.services.s3.AmazonS3 legacyClient = mock(com.amazonaws.services.s3.AmazonS3.class);
        ReflectionTestUtils.setField(service, "legacyS3Client", legacyClient);
        when(legacyClient.generatePresignedUrl(any(com.amazonaws.services.s3.model.GeneratePresignedUrlRequest.class)))
                .thenThrow(new RuntimeException("boom"));

        String url = service.getUrl("users/1/photo.png");

        assertThat(url).isEqualTo("https://s3.test-endpoint.com/test-bucket/users/1/photo.png");
    }

    @Test
    void getUrl_withoutLegacyClient_returnsEndpointUrl() {
        ReflectionTestUtils.setField(service, "legacyS3Client", null);

        String url = service.getUrl("users/1/photo.png");

        assertThat(url).isEqualTo("https://s3.test-endpoint.com/test-bucket/users/1/photo.png");
    }
```

- [ ] **Step 2: Run tests to verify they pass against current implementation**

Run: `mvn -q -Dtest=S3StorageServiceTest test`
Expected: all tests PASS — this is the documented baseline of current behavior (including the silent-fallback test, which we will delete once the fallback is removed in Task 3).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java
git commit -m "test(storage): add baseline tests for getUrl() SDK v1 presigned URL and fallback"
```

---

### Task 3: Migrate `getUrl()` to `S3Presigner` (SDK v2), remove silent fallback

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/storage/S3StorageService.java`

- [ ] **Step 1: Replace SDK v1 imports/field with SDK v2 `S3Presigner`, add a logger**

Replace the import block (lines 1-24) with:

```java
package com.devappmobile.flowfuel.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Presigner;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import java.time.Duration;
import java.net.URI;
```

- [ ] **Step 2: Replace the `legacyS3Client` field and its initialization with `S3Presigner`**

Replace:
```java
    private S3Client s3;
    private AmazonS3 legacyS3Client;
```
with:
```java
    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private S3Client s3;
    private S3Presigner presigner;
```

Replace the `@PostConstruct init()` body (the part building `legacyS3Client`, lines 66-81 in the original) so the full method reads:

```java
    @PostConstruct
    public void init() {
        var builder = S3Client.builder();
        var presignerBuilder = S3Presigner.builder();

        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            var credentialsProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));
            builder.credentialsProvider(credentialsProvider);
            presignerBuilder.credentialsProvider(credentialsProvider);
        }

        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
            presignerBuilder.region(Region.of(region));
        }

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            presignerBuilder.endpointOverride(URI.create(endpoint));
        }

        s3 = builder.build();
        presigner = presignerBuilder.build();
    }
```

- [ ] **Step 3: Rewrite `getUrl()` to use `S3Presigner`, removing the silent catch**

Replace:
```java
    @Override
    public String getUrl(String key) {
        // If we have a legacy client, generate a presigned URL (valid 15 minutes)
        if (legacyS3Client != null) {
            try {
                Instant exp = Instant.now().plus(Duration.ofMinutes(15));
                Date expiration = Date.from(exp);
                GeneratePresignedUrlRequest presignedRequest = new GeneratePresignedUrlRequest(bucket, key)
                        .withMethod(HttpMethod.GET)
                        .withExpiration(expiration);
                return legacyS3Client.generatePresignedUrl(presignedRequest).toString();
            } catch (Exception e) {
                // fallthrough to public URL
            }
        }

        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint.replaceAll("/$", "") + "/" + bucket + "/" + key;
        }
        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucket, region, key);
    }
```
with:
```java
    @Override
    public String getUrl(String key) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .getObjectRequest(getRequest)
                    .build();

            return presigner.presignGetObject(presignRequest).url().toString();
        } catch (Exception e) {
            log.error("Falha ao gerar URL pre-assinada para key={}", key, e);
            throw new RuntimeException("Falha ao gerar URL pre-assinada", e);
        }
    }
```

- [ ] **Step 4: Compile to confirm no leftover SDK v1 references**

Run: `mvn -q compile`
Expected: BUILD SUCCESS, no references to `com.amazonaws.*` remain in `S3StorageService.java`.

```bash
grep -n "com.amazonaws" src/main/java/com/devappmobile/flowfuel/storage/S3StorageService.java
```
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/storage/S3StorageService.java
git commit -m "refactor(storage): migrate getUrl() to S3Presigner (SDK v2), remove silent fallback"
```

---

### Task 4: Update tests for the new `S3Presigner`-based `getUrl()`

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java`

- [ ] **Step 1: Remove the three SDK v1 `getUrl()` tests from Task 2** (`getUrl_withLegacyClient_returnsPresignedUrl`, `getUrl_whenLegacyClientThrows_fallsBackToEndpointUrl`, `getUrl_withoutLegacyClient_returnsEndpointUrl`) and the now-unused `com.amazonaws.*` references.

- [ ] **Step 2: Add tests for the `S3Presigner`-based implementation**

```java
    @Test
    void getUrl_returnsPresignedUrlFromPresigner() throws Exception {
        software.amazon.awssdk.services.s3.S3Presigner presigner =
                mock(software.amazon.awssdk.services.s3.S3Presigner.class);
        ReflectionTestUtils.setField(service, "presigner", presigner);

        software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest presigned =
                mock(software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest.class);
        java.net.URL url = new java.net.URL("https://s3.test-endpoint.com/test-bucket/users/1/photo.png?X-Amz-Signature=abc");
        when(presigned.url()).thenReturn(url);
        when(presigner.presignGetObject(
                any(software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.class)))
                .thenReturn(presigned);

        String result = service.getUrl("users/1/photo.png");

        assertThat(result).isEqualTo(url.toString());
    }

    @Test
    void getUrl_whenPresignerThrows_propagatesException() {
        software.amazon.awssdk.services.s3.S3Presigner presigner =
                mock(software.amazon.awssdk.services.s3.S3Presigner.class);
        ReflectionTestUtils.setField(service, "presigner", presigner);
        when(presigner.presignGetObject(
                any(software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.class)))
                .thenThrow(new software.amazon.awssdk.core.exception.SdkClientException.builder()
                        .message("invalid config").build());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getUrl("users/1/photo.png"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Falha ao gerar URL pre-assinada");
    }
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `mvn -q -Dtest=S3StorageServiceTest test`
Expected: BUILD SUCCESS, all tests in `S3StorageServiceTest` pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java
git commit -m "test(storage): update getUrl() tests for S3Presigner (SDK v2), add error propagation test"
```

---

### Task 5: Remove AWS SDK v1 dependency from `pom.xml`

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Remove the SDK v1 dependency block**

Find and delete (around line 132-136):
```xml
		<!-- AWS SDK v1 for presigned URL generation (compatible with Backblaze) -->
		<dependency>
			<groupId>com.amazonaws</groupId>
			<artifactId>aws-java-sdk-s3</artifactId>
			<version>1.12.548</version>
		</dependency>
```

- [ ] **Step 2: Confirm no remaining references to the v1 artifact**

```bash
mvn -q dependency:tree | grep -i "com.amazonaws"
grep -rn "com.amazonaws" src/main src/test
```
Expected: no output from either command.

- [ ] **Step 3: Run the full build and test suite**

```bash
mvn -q clean verify
```
Expected: BUILD SUCCESS, including `S3StorageServiceTest` and `UserControllerIntegrationTest` (the profile photo upload/download flow that exercises `StorageService`).

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "chore(storage): remove aws-java-sdk-s3 (SDK v1) dependency"
```

---

### Task 6: Mark roadmap item as done

**Files:**
- Modify: `docs/roadmap/phase-3/M3-s3-sdk-v2-migration.md`

- [ ] **Step 1: Update the frontmatter status and checklist**

Change line 7 from `status: pending` to `status: done`.

Update the checklist (lines 110-117) to:
```markdown
- [x] Analisar código atual
- [x] Criar testes de baseline para `S3StorageService` (antes da migração)
- [x] Implementar solução (migração para `S3Presigner` SDK v2)
- [x] Remover dependência do SDK v1
- [x] Adicionar/atualizar testes
- [ ] Atualizar documentação
- [x] Executar testes de regressão
- [ ] Abrir PR
```

- [ ] **Step 2: Commit**

```bash
git add docs/roadmap/phase-3/M3-s3-sdk-v2-migration.md
git commit -m "docs(roadmap): mark M3 S3 SDK v2 migration as done"
```

---

## Final Verification

- [ ] Run `mvn -q clean verify` one more time end-to-end — expect BUILD SUCCESS.
- [ ] Run `grep -rn "com.amazonaws" src pom.xml` — expect no output.
- [ ] Manually confirm in staging (per the spec's risk note) that a presigned URL generated by `S3Presigner` is accepted by the Backblaze B2 S3-compatible endpoint and consumable by the frontend/Android client before deploying to production.
