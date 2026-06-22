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
