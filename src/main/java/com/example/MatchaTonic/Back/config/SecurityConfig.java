package com.example.MatchaTonic.Back.config;

import com.example.MatchaTonic.Back.security.oauth.CustomOAuth2UserService;
import com.example.MatchaTonic.Back.security.jwt.JwtAuthenticationFilter;
import com.example.MatchaTonic.Back.security.jwt.JwtTokenProvider;
import com.example.MatchaTonic.Back.security.oauth.OAuth2SuccessHandler;
import jakarta.servlet.http.HttpServletResponse;
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

    @Bean
    public ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // JWT를 사용하므로 세션은 가급적 STATELESS가 좋으나, 기존 설정(IF_REQUIRED) 유지
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login/**", "/oauth2/**", "/health", "/images/**", "/static/**", "/favicon.ico").permitAll()
                        .requestMatchers("/api/notion/oauth/callback").permitAll()
                        .requestMatchers("/api/users/**").permitAll()
                        .requestMatchers("/api/manuals/**").permitAll()
                        // 프로젝트 및 채팅 관련 API는 인증 필수 (CORS 리다이렉트 방지 대상)
                        .requestMatchers("/api/projects/**", "/api/project/**").authenticated()
                        .requestMatchers("/api/notion/**").authenticated()
                        .requestMatchers("/api/chat/**").authenticated()
                        .requestMatchers("/ws-stomp/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated()
                )

                // [핵심 추가] 인증 실패 시 리다이렉트하지 않고 401/403 에러 반환
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            // 인증되지 않은 사용자가 접근 시 401 반환
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"status\":401, \"code\":\"UNAUTHORIZED\", \"message\":\"로그인이 필요합니다.\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            // 권한이 없는 사용자가 접근 시 403 반환
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"status\":403, \"code\":\"FORBIDDEN\", \"message\":\"권한이 없습니다.\"}");
                        })
                )

                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(oAuth2SuccessHandler)
                )

                .logout(logout -> logout
                        .logoutUrl("/api/users/logout")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            String redirectUri = request.getParameter("post_logout_redirect_uri");
                            if (redirectUri == null || redirectUri.isEmpty()) {
                                redirectUri = "https://promate.ai.kr";
                            }
                            response.sendRedirect(redirectUri);
                        })
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID", "Authorization")
                );

        http.addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:3000",
                "https://promate.ai.kr",
                "http://promate.ai.kr",
                "https://api.promate.ai.kr"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Set-Cookie"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
