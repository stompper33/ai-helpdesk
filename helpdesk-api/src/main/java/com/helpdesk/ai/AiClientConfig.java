package com.helpdesk.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiClientConfig {

    /** A RestClient dedicated to ai-service, with its own base URL, timeouts and auth header. */
    @Bean
    RestClient aiRestClient(RestClient.Builder builder, AiServiceProperties props) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.connectTimeout());
        requestFactory.setReadTimeout(props.readTimeout());

        builder = builder.baseUrl(props.baseUrl()).requestFactory(requestFactory);
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            builder = builder.defaultHeader("X-Api-Key", props.apiKey());
        }
        return builder.build();
    }
}
