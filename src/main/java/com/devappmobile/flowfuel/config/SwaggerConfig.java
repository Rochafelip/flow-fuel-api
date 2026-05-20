package com.devappmobile.flowfuel.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("FlowFuel API")
                        .version("1.0.0")
                        .description("API para gerenciamento de combustível de veículos")
                        .contact(new Contact()
                                .name("DevAppMobile")
                                .email("support@devappmobile.com")));
    }
}
