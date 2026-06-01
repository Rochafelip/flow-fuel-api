package com.devappmobile.flowfuel.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Validacao em runtime da configuracao do Sentry (FLOW-017, ADR-008).
 *
 * <p>O SDK e ligado/desligado pela presenca de {@code sentry.dsn}. Sem DSN o
 * Sentry fica off e nao quebra o deploy (dev e o default de staging/prod sem a
 * env var). Esta classe apenas:
 * <ul>
 *   <li>loga, no startup, se o Sentry esta <b>ativo</b> ou <b>desativado</b>;</li>
 *   <li>quando ha DSN, valida o <b>formato</b> ({@code {protocolo}://{chave}@{host}/{projectId}})
 *       e faz <i>fail-fast</i> se estiver malformado — evita subir achando que o
 *       monitoramento esta ligado quando, na verdade, o SDK silenciaria o erro e
 *       descartaria os eventos. A checagem replica a do parser do proprio SDK
 *       ({@code io.sentry.Dsn}, que e package-private e nao da pra reusar).</li>
 * </ul>
 *
 * <p>O DSN nunca e logado: ele carrega a chave publica do projeto.
 */
@Configuration
public class SentryConfig {

    private static final Logger log = LoggerFactory.getLogger(SentryConfig.class);

    @Value("${sentry.dsn:}")
    private String dsn;

    @Value("${sentry.environment:}")
    private String environment;

    @PostConstruct
    void validateDsn() {
        if (dsn == null || dsn.isBlank()) {
            log.info("Sentry DESATIVADO: sentry.dsn ausente — erros nao serao reportados.");
            return;
        }

        try {
            parseDsn(dsn);
        } catch (IllegalArgumentException ex) {
            // Mensagem do motivo, sem o DSN em si (que contem a chave publica).
            throw new IllegalStateException(
                    "sentry.dsn esta definido mas e invalido: " + ex.getMessage(), ex);
        }

        log.info("Sentry ATIVO: environment={} — erros (ERROR) serao reportados.",
                environment == null || environment.isBlank() ? "(default)" : environment);
    }

    /** Protocolos aceitos pelo Sentry. */
    private static final Set<String> VALID_SCHEMES = Set.of("http", "https");

    /**
     * Valida o formato do DSN espelhando o parser do SDK: exige protocolo
     * http(s), chave publica (userInfo), host e um projectId (ultimo segmento do
     * path). Lanca {@link IllegalArgumentException} descrevendo o que falta.
     */
    private static void parseDsn(String dsn) {
        final URI uri;
        try {
            uri = new URI(dsn).normalize();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("nao e uma URI valida.", e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !VALID_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("protocolo deve ser http ou https.");
        }

        String userInfo = uri.getUserInfo();
        if (userInfo == null || userInfo.isEmpty()) {
            throw new IllegalArgumentException("falta a chave publica.");
        }

        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("falta o host.");
        }

        String path = uri.getPath() == null ? "" : uri.getPath();
        int lastSlash = path.lastIndexOf('/');
        String projectId = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (projectId.isEmpty()) {
            throw new IllegalArgumentException("falta o Project Id.");
        }
    }

    /**
     * Endpoint de diagnostico, somente em staging: lanca uma excecao nao tratada
     * para forcar um HTTP 500 e confirmar, no painel do Sentry, que os eventos
     * ERROR estao chegando. Cai em {@code GlobalExceptionHandler#handleGeneric},
     * que loga em ERROR — nivel que o {@code SentryAppender} encaminha.
     *
     * <p>Nunca exposto em prod. Exige autenticacao (qualquer rota nao listada no
     * {@code SecurityConfig} cai em {@code authenticated()}).
     */
    @RestController
    @Profile("staging")
    static class SentryDebugController {

        @GetMapping("/internal/sentry/debug")
        String triggerError() {
            throw new IllegalStateException(
                    "Sentry debug (FLOW-017): excecao forcada para validar o envio de eventos.");
        }
    }
}
