package com.devappmobile.flowfuel.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .servers(List.of(
                        new Server().url("https://api.flowfuel.app").description("Produção"),
                        new Server().url("https://desenv.api.flowfuel.app").description("Desenvolvimento"),
                        new Server().url("http://localhost:8090").description("Local")
                ))
                .info(new Info()
                        .title("FlowFuel API")
                        .version("1.0.0")
                        .description("API para gerenciamento de combustível de veículos")
                        .contact(new Contact()
                                .name("DevAppMobile")
                                .email("support@devappmobile.com")));
    }
}
