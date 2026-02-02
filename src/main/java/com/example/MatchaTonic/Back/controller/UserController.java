package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.repository.login.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "User", description = "사용자 정보 및 인증 관리 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @Operation(summary = "로그인 유저 정보 조회 (SYS-01)", description = "현재 세션에 로그인된 사용자의 상세 정보를 반환합니다.")
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@Parameter(hidden = true) @AuthenticationPrincipal OAuth2User principal) {

        // 1. 로그인이 안 된 경우
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요합니다.");
        }

        // 2. 구글 세션에서 이메일 추출
        String email = principal.getAttribute("email");

        // 3. DB 조회 후 결과 반환
        return userRepository.findByEmail(email)
                .map(user -> {
                    Map<String, Object> userInfo = new HashMap<>();
                    userInfo.put("id", user.getId());
                    userInfo.put("email", user.getEmail());
                    userInfo.put("name", user.getName());
                    userInfo.put("role", user.getRole());

                    String googlePicture = principal.getAttribute("picture");
                    userInfo.put("picture", googlePicture);

                    return ResponseEntity.ok().body((Object) userInfo);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("사용자를 찾을 수 없습니다."));
    }
}