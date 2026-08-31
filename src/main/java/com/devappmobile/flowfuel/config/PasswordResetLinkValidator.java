package com.devappmobile.flowfuel.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fail-fast do link de redefinicao de senha em producao/staging.
 *
 * <p>{@code flowfuel.password-reset.link-base-url} tem como default
 * {@code http://localhost:5173/reset-password} em {@code application.properties},
 * valido para {@code dev}/{@code test}. Em {@code prod}/{@code staging}, se a
 * env var {@code PASSWORD_RESET_LINK_BASE_URL} nao for configurada, o
 * {@code SmtpPasswordResetNotifier} enviaria emails reais com um link
 * {@code localhost} quebrado para o usuario. Esta classe impede a aplicacao de
 * subir nesse caso — mesmo padrao do antigo {@code ActivationLinkValidator}.
 */
@Configuration
@Profile({"prod", "staging"})
public class PasswordResetLinkValidator {

    @Value("${flowfuel.password-reset.link-base-url:}")
    private String linkBaseUrl;

    @PostConstruct
    void validate() {
        if (linkBaseUrl == null || linkBaseUrl.isBlank() || linkBaseUrl.contains("localhost")) {
            throw new IllegalStateException(
                    "PASSWORD_RESET_LINK_BASE_URL nao pode ser vazio ou apontar para "
                            + "localhost em producao/staging.");
        }
    }
}
