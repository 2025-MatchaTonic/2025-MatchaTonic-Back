package com.example.MatchaTonic.Back.security.oauth;

import com.example.MatchaTonic.Back.security.jwt.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        // 구글에서 넘겨준 이메일 정보 가져오기
        String email = (String) oAuth2User.getAttributes().get("email");

        // JWT 토큰 생성
        String token = jwtTokenProvider.createToken(email, "ROLE_USER");

        String targetUrl = UriComponentsBuilder.fromUriString("https://promate.ai.kr/oauth2/redirect")
                .queryParam("token", token)
                .build().toUriString();


        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}