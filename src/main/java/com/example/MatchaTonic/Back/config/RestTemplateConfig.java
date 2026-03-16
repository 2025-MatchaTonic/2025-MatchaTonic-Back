package com.example.MatchaTonic.Back.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        // 1. 서버와 연결을 맺는 시간
        factory.setConnectTimeout(5000);

        // 2. 서버로부터 데이터를 읽어오는 시간
        factory.setReadTimeout(120000);

        return new RestTemplate(factory);
    }
}