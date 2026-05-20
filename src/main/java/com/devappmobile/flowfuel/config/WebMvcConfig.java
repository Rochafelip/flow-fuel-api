package com.devappmobile.flowfuel.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(@NonNull PathMatchConfigurer configurer) {
        // Aplica o prefixo /api/v1 a todos os RestController em subpacotes
        // (HomeController, na raiz, fica fora — mantém o health check em "/").
        configurer.addPathPrefix("/api/v1", c ->
                c.isAnnotationPresent(RestController.class)
                        && !c.getPackageName().equals("com.devappmobile.flowfuel"));
    }
}
