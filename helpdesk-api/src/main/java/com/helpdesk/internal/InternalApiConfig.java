package com.helpdesk.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InternalApiConfig {

    @Bean
    FilterRegistrationBean<ServiceKeyFilter> serviceKeyFilter(@Value("${helpdesk.internal-api.key:}") String key) {
        var registration = new FilterRegistrationBean<>(new ServiceKeyFilter(key));
        registration.addUrlPatterns("/internal/*");
        registration.setName("serviceKeyFilter");
        return registration;
    }
}
