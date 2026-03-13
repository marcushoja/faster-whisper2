package de.hoja.fasterwhisper2.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient groqWebClient() {
        return WebClient.builder()
            .baseUrl("https://api.groq.com/openai/v1")
            .build();
    }
}
