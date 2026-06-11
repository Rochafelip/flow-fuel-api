# A3 — Activation Link Validator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fail-fast Spring `@Configuration` that prevents the application from starting in `prod`/`staging` if `flowfuel.account-activation.link-base-url` is empty or still points to `localhost`, eliminating the risk of broken activation links being emailed to real users.

**Architecture:** A single new class `ActivationLinkValidator`, annotated `@Configuration` + `@Profile({"prod", "staging"})`, injects `flowfuel.account-activation.link-base-url` via `@Value` and validates it in a `@PostConstruct` method — mirroring the existing `JwtProdValidator` / `SentryConfig` fail-fast pattern. No changes are needed to `application.properties` or the profile-specific files: the existing `localhost` default stays as-is for `dev`/`test`, and the new validator is what blocks `prod`/`staging` from using it.

**Tech Stack:** Spring Boot (`@Configuration`, `@Profile`, `@PostConstruct`, `@Value`), JUnit 5 + AssertJ, `ReflectionTestUtils` (mirrors `SentryConfigTest`).

---

## Task 1: Create `ActivationLinkValidator` with failing test first

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/config/ActivationLinkValidator.java`
- Test: `src/test/java/com/devappmobile/flowfuel/config/ActivationLinkValidatorTest.java`

### Step 1: Write the failing test

Create `src/test/java/com/devappmobile/flowfuel/config/ActivationLinkValidatorTest.java`:

```java
package com.devappmobile.flowfuel.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationLinkValidatorTest {

    private ActivationLinkValidator validatorWithUrl(String url) {
        ActivationLinkValidator validator = new ActivationLinkValidator();
        ReflectionTestUtils.setField(validator, "linkBaseUrl", url);
        return validator;
    }

    @Test
    void urlValidaDeProducao_naoLanca() {
        assertThatCode(() -> validatorWithUrl("https://app.flowfuel.com/activate").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void urlNula_lancaFailFast() {
        assertThatThrownBy(() -> validatorWithUrl(null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_ACTIVATION_LINK_BASE_URL");
    }

    @Test
    void urlVazia_lancaFailFast() {
        assertThatThrownBy(() -> validatorWithUrl("").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_ACTIVATION_LINK_BASE_URL");
    }

    @Test
    void urlEmBranco_lancaFailFast() {
        assertThatThrownBy(() -> validatorWithUrl("   ").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_ACTIVATION_LINK_BASE_URL");
    }

    @Test
    void urlComLocalhost_lancaFailFast() {
        assertThatThrownBy(() -> validatorWithUrl("http://localhost:5173/activate").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost");
    }
}
```

### Step 2: Run the test to verify it fails

Run:

```bash
./mvnw test -Dtest=ActivationLinkValidatorTest
```

Expected: compilation FAILURE — `ActivationLinkValidator` does not exist yet (`cannot find symbol`).

### Step 3: Create the validator

Create `src/main/java/com/devappmobile/flowfuel/config/ActivationLinkValidator.java`:

```java
package com.devappmobile.flowfuel.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fail-fast do link de ativacao de conta em producao/staging (FLOW A3).
 *
 * <p>{@code flowfuel.account-activation.link-base-url} tem como default
 * {@code http://localhost:5173/activate} em {@code application.properties}, valido
 * para {@code dev}/{@code test}. Em {@code prod}/{@code staging}, se a env var
 * {@code ACCOUNT_ACTIVATION_LINK_BASE_URL} nao for configurada, o
 * {@code SmtpAccountActivationNotifier} enviaria emails reais com um link
 * {@code localhost} quebrado para o usuario. Esta classe impede a aplicacao de
 * subir nesse caso.
 */
@Configuration
@Profile({"prod", "staging"})
public class ActivationLinkValidator {

    @Value("${flowfuel.account-activation.link-base-url:}")
    private String linkBaseUrl;

    @PostConstruct
    void validate() {
        if (linkBaseUrl == null || linkBaseUrl.isBlank() || linkBaseUrl.contains("localhost")) {
            throw new IllegalStateException(
                    "ACCOUNT_ACTIVATION_LINK_BASE_URL nao pode ser vazio ou apontar para "
                            + "localhost em producao/staging.");
        }
    }
}
```

### Step 4: Run the test to verify it passes

Run:

```bash
./mvnw test -Dtest=ActivationLinkValidatorTest
```

Expected: `Tests run: 5, Failures: 0, Errors: 0` — all 5 tests pass.

### Step 5: Run the full test suite to confirm no regressions

Run:

```bash
./mvnw test
```

Expected: `BUILD SUCCESS`. The new `@Profile({"prod", "staging"})` bean is not active under the default `dev`/`test` profiles used by existing tests, so no existing context-loading test should pick it up or fail.

### Step 6: Commit

```bash
git add src/main/java/com/devappmobile/flowfuel/config/ActivationLinkValidator.java src/test/java/com/devappmobile/flowfuel/config/ActivationLinkValidatorTest.java
git commit -m "feat: fail-fast validation of activation link base URL in prod/staging"
```

---

## Task 2: Operational verification (no code change)

This step has no code artifact but is a hard prerequisite before merging/deploying, per the spec's risk section: after this change ships, `prod`/`staging` will refuse to start unless `ACCOUNT_ACTIVATION_LINK_BASE_URL` is set to a non-`localhost` URL.

- [ ] **Step 1: Confirm with infra/Render that `ACCOUNT_ACTIVATION_LINK_BASE_URL` is currently set in the production environment**, pointing to the real frontend activation URL (e.g. `https://app.flowfuel.com/activate`).
- [ ] **Step 2: Confirm the same for the staging environment**, if staging is deployed.
- [ ] **Step 3: If either env var is missing or set to a `localhost` URL, set it in Render's environment configuration *before* deploying this change.** Do not deploy until this is confirmed — otherwise the affected environment will fail to start.

---

## Self-Review Notes

- **Spec coverage:** `@Configuration` + `@Profile({"prod","staging"})` ✅; `@Value` injection ✅; `@PostConstruct` throwing `IllegalStateException` for null/blank/`localhost` ✅; error message references `ACCOUNT_ACTIVATION_LINK_BASE_URL` ✅; `application.properties` left unchanged (default remains for dev/test, validator is the prod/staging gate) ✅; test coverage for both failing and passing cases ✅; operational checklist item for confirming the env var in prod before deploy ✅.
- **No `@SpringBootTest`/`ApplicationContextRunner` needed:** the codebase's existing equivalent (`SentryConfigTest`) tests the `@PostConstruct` method directly via `ReflectionTestUtils`, without booting a Spring context — this is faster and was followed here for consistency. Profile activation (`@Profile({"prod","staging"})`) is a one-line Spring annotation already proven by `JwtProdValidator`; a full context-loading test would mostly be testing Spring's own profile-matching, not this class's logic.
- **Type/signature consistency:** field name `linkBaseUrl` and method name `validate()` match between the validator and its test.
