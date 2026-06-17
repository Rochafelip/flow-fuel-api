package com.devappmobile.flowfuel.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecretConfigTest {

    private static final Pattern JWT_SECRET_LINE = Pattern.compile("(?m)^jwt\\.secret=(.*)$");

    @Test
    void applicationProperties_naoDeveConterSegredoJwtPadraoHardcoded() throws IOException {
        // Le diretamente de src/main/resources: via classloader, o classpath de teste
        // (que define seu proprio jwt.secret) e' resolvido antes do de main, mascarando
        // o conteudo que este teste precisa inspecionar.
        Path path = Path.of("src/main/resources/application.properties");
        String content = Files.readString(path);

        Matcher matcher = JWT_SECRET_LINE.matcher(content);
        assertThat(matcher.find())
                .as("propriedade jwt.secret deve existir em application.properties")
                .isTrue();

        String value = matcher.group(1).trim();
        assertThat(value)
                .as("jwt.secret nao deve ter um valor default hardcoded — apenas ${JWT_SECRET} sem fallback")
                .isEqualTo("${JWT_SECRET}");
    }
}
