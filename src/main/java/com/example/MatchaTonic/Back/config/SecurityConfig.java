package com.example.MatchaTonic.Back.config;

import com.example.MatchaTonic.Back.security.oauth.CustomOAuth2UserService;
import com.example.MatchaTonic.Back.security.jwt.JwtAuthenticationFilter;
import com.example.MatchaTonic.Back.security.jwt.JwtTokenProvider;
import com.example.MatchaTonic.Back.security.oauth.OAuth2SuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * [중요] ForwardedHeaderFilter 설정
     * AWS 로드밸런서(ALB)는 클라이언트와 HTTPS로 통신하지만, 내부 서버(EC2)에는 HTTP로 요청을 전달합니다.
     * 이 필터는 ALB가 보낸 'X-Forwarded-Proto' 헤더를 읽어 스프링이 스스로를 HTTPS로 인식하게 합니다.
     * 이 설정이 없으면 구글 로그인 시 redirect_uri가 http로 생성되어 오류(400)가 발생합니다.
     */
    @Bean
    public ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 및 기본 로그인 폼 비활성화 (JWT 사용 방식)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource())) // 아래 정의된 CORS 설정 적용
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // 세션 정책: OAuth2 인증 과정에서 일시적인 세션 사용이 필요하므로 IF_REQUIRED 설정
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                .authorizeHttpRequests(auth -> auth
                        // 1. 인증 없이 접근 가능한 경로 (로그인, 건강검진, 소셜 로그인 등)
                        .requestMatchers("/", "/login/**", "/oauth2/**", "/health").permitAll()
                        .requestMatchers("/api/users/**").permitAll()
                        .requestMatchers("/api/manuals/**").permitAll()

                        // 2. 웹소켓 연결 경로 허용
                        .requestMatchers("/ws-stomp/**").permitAll()

                        // 3. Swagger API 문서 경로 허용
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // 4. 그 외 모든 요청은 인증 필요
                        .anyRequest().authenticated()
                )

                // OAuth2 로그인 설정
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService)) // 구글 유저 정보 처리 서비스
                        .successHandler(oAuth2SuccessHandler) // 로그인 성공 후 JWT 발행 및 프론트 리다이렉트 처리
                )

                // 로그아웃 설정
                .logout(logout -> logout
                        .logoutUrl("/api/users/logout")
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true) // 세션 무효화
                        .deleteCookies("JSESSIONID") // 쿠키 삭제
                );

        // [JWT 필터 추가] UsernamePasswordAuthenticationFilter 실행 전 JWT 인증 먼저 수행
        http.addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS(Cross-Origin Resource Sharing) 설정
     * 프론트엔드(promate.ai.kr)에서 백엔드(api.promate.ai.kr)로 API 요청을 보낼 때 차단되지 않도록 허용합니다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true); // 쿠키/인증 정보 포함 허용

        config.setAllowedOriginPatterns(List.of(
                "http://localhost:3000",       // 로컬 테스트용 프론트
                "https://promate.ai.kr",      // 운영 중인 프론트엔드 도메인
                "http://promate.ai.kr",       // HTTP 접속 대비 (ALB에서 HTTPS로 리다이렉트됨)
                "https://api.promate.ai.kr"   // 백엔드 자기 자신 도메인
        ));

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));

        // 프론트엔드에서 응답 헤더에 담긴 Authorization(JWT)을 읽을 수 있도록 허용
        config.setExposedHeaders(List.of("Authorization", "Set-Cookie"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}