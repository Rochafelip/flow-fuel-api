package com.devappmobile.flowfuel.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordResetLinkValidatorTest {

    private PasswordResetLinkValidator validatorWithUrl(String url) {
        PasswordResetLinkValidator validator = new PasswordResetLinkValidator();
        ReflectionTestUtils.setField(validator, "linkBaseUrl", url);
        return validator;
    }

    @Test
    void urlValidaDeProducao_naoLanca() {
        assertThatCode(() -> validatorWithUrl("https://app.flowfuel.com/reset-password").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void urlNula_lancaFailFast() {
        assertThatThrownBy(() -> validatorWithUrl(null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PASSWORD_RESET_LINK_BASE_URL");
    }

    @Test
    void urlVazia_lancaFailFast() {
        assertThatThrownBy(() -> validatorWithUrl("").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PASSWORD_RESET_LINK_BASE_URL");
    }

    @Test
    void urlEmBranco_lancaFailFast() {
        assertThatThrownBy(() -> validatorWithUrl("   ").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PASSWORD_RESET_LINK_BASE_URL");
    }

    @Test
    void urlComLocalhost_lancaFailFast() {
        assertThatThrownBy(() -> validatorWithUrl("http://localhost:5173/reset-password").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost");
    }
}
