# B4 — Remover segredo JWT de dev commitado — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the hardcoded `flowfuel-dev-only-secret-change-in-production-32chars` default from `application.properties`, forcing every profile — including local `dev` — to set `JWT_SECRET` explicitly.

**Architecture:** `application.properties` currently resolves `jwt.secret` as `${JWT_SECRET:flowfuel-dev-only-secret-change-in-production-32chars}` — a default that applies to every profile that doesn't override it (i.e. `dev`, the active profile by default). `application-staging.properties` and `application-prod.properties` already use `${JWT_SECRET}` with **no fallback**, which makes Spring fail at startup with an unresolved placeholder error if the env var is missing — that's the existing, proven fail-fast mechanism (the `JwtProdValidator` in `JwtProdValidator.java:16` only adds an extra length check on top, scoped to `@Profile("prod")`). The fix is to make `application.properties` match that same no-fallback pattern, so `dev` behaves consistently with `staging`/`prod`. The test profile already defines its own secret in `src/test/resources/application.properties:7` and is unaffected.

**Tech Stack:** Spring Boot 3.5.7, JUnit 5, AssertJ, Maven.

---

## File Structure

- Modify: `src/main/resources/application.properties:25` — remove the hardcoded default from `jwt.secret`.
- Modify: `.env.example` — document `JWT_SECRET` as a required local var.
- Modify: `README.md` ("Como rodar" section) — explain how to generate/set `JWT_SECRET` for local dev.
- Create: `src/test/java/com/devappmobile/flowfuel/config/JwtSecretConfigTest.java` — regression test guarding against a hardcoded fallback secret creeping back into `application.properties`.

No production Java code changes are needed: `JwtProdValidator` and Spring's own placeholder resolution already provide the fail-fast behavior once the default is removed.

---

### Task 1: Add a regression test against a hardcoded JWT fallback

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/config/JwtSecretConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecretConfigTest {

    private static final Pattern JWT_SECRET_LINE = Pattern.compile("(?m)^jwt\\.secret=(.*)$");

    @Test
    void applicationProperties_naoDeveConterSegredoJwtPadraoHardcoded() throws IOException {
        String content = readClasspathResource("application.properties");

        Matcher matcher = JWT_SECRET_LINE.matcher(content);
        assertThat(matcher.find())
                .as("propriedade jwt.secret deve existir em application.properties")
                .isTrue();

        String value = matcher.group(1).trim();
        assertThat(value)
                .as("jwt.secret nao deve ter um valor default hardcoded — apenas ${JWT_SECRET} sem fallback")
                .isEqualTo("${JWT_SECRET}");
    }

    private String readClasspathResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(is).as("recurso %s deve existir no classpath", name).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=JwtSecretConfigTest -pl .`
Expected: FAIL — `value` is `${JWT_SECRET:flowfuel-dev-only-secret-change-in-production-32chars}`, not `${JWT_SECRET}`.

- [ ] **Step 3: Commit the failing test**

```bash
git add src/test/java/com/devappmobile/flowfuel/config/JwtSecretConfigTest.java
git commit -m "test(config): add regression test against hardcoded JWT dev secret"
```

---

### Task 2: Remove the hardcoded default from `application.properties`

**Files:**
- Modify: `src/main/resources/application.properties:23-25`

- [ ] **Step 1: Edit the property**

Change:

```properties
# Chave JWT — OBRIGATÓRIO em producao: defina via variavel de ambiente JWT_SECRET
# Minimo 32 caracteres para HS256/HS512
jwt.secret=${JWT_SECRET:flowfuel-dev-only-secret-change-in-production-32chars}
```

to:

```properties
# Chave JWT — OBRIGATORIA em todos os perfis (incluindo dev). Sem fallback: se
# JWT_SECRET nao estiver definida no ambiente, a aplicacao NAO sobe (erro de
# placeholder nao resolvido do Spring). Minimo 32 caracteres para HS256/HS512.
# Gere um valor local com: openssl rand -base64 32
jwt.secret=${JWT_SECRET}
```

- [ ] **Step 2: Run the regression test to verify it passes**

Run: `mvn test -Dtest=JwtSecretConfigTest -pl .`
Expected: PASS

- [ ] **Step 3: Run the full test suite to confirm nothing else depended on the removed default**

Run: `mvn test`
Expected: All tests PASS (the test profile in `src/test/resources/application.properties:7` already provides its own `jwt.secret`, so it is unaffected).

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "fix(config): remove hardcoded dev JWT secret default"
```

---

### Task 3: Document `JWT_SECRET` in `.env.example`

**Files:**
- Modify: `.env.example`

- [ ] **Step 1: Add the `JWT_SECRET` entry**

Current file:

```
# Copie este arquivo para .env e preencha com suas credenciais reais.
# NUNCA comite o arquivo .env no Git (já está no .gitignore).

B2_S3_ENDPOINT=https://s3.<region>.backblazeb2.com
B2_S3_REGION=<region>
B2_S3_ACCESS_KEY=<sua-application-key-id>
B2_S3_SECRET=<sua-application-key>
B2_BUCKET_NAME=<nome-do-bucket>
```

New file:

```
# Copie este arquivo para .env e preencha com suas credenciais reais.
# NUNCA comite o arquivo .env no Git (já está no .gitignore).

# Segredo JWT (HS256/HS512), minimo 32 caracteres. Obrigatorio em todos os
# perfis, incluindo dev — a aplicacao nao sobe sem ele. Gere um valor com:
#   openssl rand -base64 32
JWT_SECRET=<gere-com-openssl-rand--base64-32>

B2_S3_ENDPOINT=https://s3.<region>.backblazeb2.com
B2_S3_REGION=<region>
B2_S3_ACCESS_KEY=<sua-application-key-id>
B2_S3_SECRET=<sua-application-key>
B2_BUCKET_NAME=<nome-do-bucket>
```

- [ ] **Step 2: Commit**

```bash
git add .env.example
git commit -m "docs: document required JWT_SECRET in .env.example"
```

---

### Task 4: Update README with local setup instructions

**Files:**
- Modify: `README.md:72-84` (seção "Como rodar")

- [ ] **Step 1: Edit the section**

Current:

```markdown
## Como rodar

Pré-requisitos: JDK 21 e Maven.

```bash
# Build
mvn clean package -DskipTests

# Executar
java -jar target/flowfuel-0.0.1-SNAPSHOT.jar
# ou
mvn spring-boot:run
```
```

New:

```markdown
## Como rodar

Pré-requisitos: JDK 21 e Maven.

A aplicação exige a variável de ambiente `JWT_SECRET` (mínimo 32 caracteres)
em **todos os perfis, incluindo `dev`** — sem ela, a inicialização falha.
Gere um valor local com:

```bash
export JWT_SECRET=$(openssl rand -base64 32)
```

```bash
# Build
mvn clean package -DskipTests

# Executar
java -jar target/flowfuel-0.0.1-SNAPSHOT.jar
# ou
mvn spring-boot:run
```
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: explain required JWT_SECRET setup for local dev"
```

---

### Task 5: Manual verification

**Files:** none (manual run only)

- [ ] **Step 1: Verify the app fails to start in dev without `JWT_SECRET`**

Run:

```bash
unset JWT_SECRET
mvn spring-boot:run
```

Expected: Startup fails with a Spring `IllegalArgumentException`/`PlaceholderResolutionException`-style error mentioning the unresolved `${JWT_SECRET}` placeholder. Stop the process (Ctrl+C).

- [ ] **Step 2: Verify the app starts normally in dev with `JWT_SECRET` set**

Run:

```bash
export JWT_SECRET=$(openssl rand -base64 32)
mvn spring-boot:run
```

Expected: Application starts normally on port 8090 (or `$PORT`). Stop the process (Ctrl+C) once confirmed.

- [ ] **Step 3: Run the full test suite one more time**

Run: `mvn test`
Expected: All tests PASS, with no manual env var configuration needed (test profile supplies its own secret).

---

## Self-Review Notes

- **Spec coverage:** every requirement and acceptance criterion in `docs/roadmap/phase-4/B4-remove-dev-jwt-secret.md` maps to a task above — default removed (Task 2), test profile unaffected (verified Task 2 Step 3 / Task 5 Step 3), docs updated (Tasks 3–4), production behavior (`JwtProdValidator`) untouched (no changes to prod files or validator), manual dev fail-fast/success verification (Task 5).
- **Chosen option:** the roadmap doc's "Option A" (require `JWT_SECRET` explicitly via env var) was chosen over "Option B" (invalid placeholder) because `staging`/`prod` already use the no-fallback pattern successfully — `dev` matching that pattern keeps all profiles consistent and avoids inventing new validation logic.
