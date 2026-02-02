package com.example.MatchaTonic.Back.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI promateOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Promate API 명세서")
                        .description("졸업 프로젝트 Promate의 백엔드 API 문서입니다.")
                        .version("v1.0.0"))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Server")
                ));
    }
}