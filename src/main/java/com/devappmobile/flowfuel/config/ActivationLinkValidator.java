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
