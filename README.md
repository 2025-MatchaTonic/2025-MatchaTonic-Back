# ProMate - Backend Server

> 조율자 부재로 의사결정과 협업 관리에 어려움을 겪는 초보 대학생 팀 프로젝트를 위한  
> **AI 기반 단계별 의사결정 및 역할별 업무 가이드 지원 서비스**

- **Team:** 27 - Matcha Tonic
- **Service URL:** [https://promate.ai.kr](https://promate.ai.kr)    현재는 서버 종료상태

---

## 1. Project Description

ProMate 백엔드 서버는 Spring Boot 기반으로 구현되었으며, 프론트엔드와 AI 서버 사이의 중계 역할을 담당합니다.  
주요 기능으로는 Google OAuth2 소셜 로그인, JWT 기반 인증, WebSocket 실시간 채팅, AI 서버 연동, Notion API 워크스페이스 자동 생성이 포함됩니다.

---

## 2. Source Code Description

```
Back/
├── config/                  # Security, CORS, WebSocket 설정
├── controller/              # REST API 및 채팅 컨트롤러
├── service/                 # 비즈니스 로직 (프로젝트, 채팅, Notion, AI 연동)
├── entity/                  # JPA 엔티티 (User, Project, ChatMessage 등)
├── dto/                     # 요청/응답 DTO
├── repository/              # Spring Data JPA Repository
├── security/
│   ├── jwt/                 # JWT 토큰 발급 및 인증 필터
│   └── oauth/               # OAuth2 사용자 서비스 및 성공 핸들러
└── exception/               # GlobalExceptionHandler
```

---

## 3. How to Build

### Prerequisites

- Java 21
- Gradle 8.x
- MySQL 8.x (또는 Amazon RDS)
- Redis

### 빌드

```bash
# 프로젝트 클론
git clone https://github.com/2025-MatchaTonic/2025-MatchaTonic-Back.git
cd 2025-MatchaTonic-Back

# 빌드
./gradlew clean build

# 빌드 결과물 확인
ls build/libs/
```

---

## 4. How to Install

### 환경변수 설정

`src/main/resources/application.yml` 또는 환경변수로 아래 값을 설정합니다.

```yaml
spring:
  datasource:
    url: jdbc:mysql://<DB_HOST>:3306/<DB_NAME>
    username: <DB_USERNAME>
    password: <DB_PASSWORD>

  security:
    oauth2:
      client:
        registration:
          google:
            client-id: <GOOGLE_CLIENT_ID>
            client-secret: <GOOGLE_CLIENT_SECRET>

  data:
    redis:
      host: <REDIS_HOST>
      port: 6379

jwt:
  secret: <JWT_SECRET_KEY>
  access-token-expiration: 3600000
  refresh-token-expiration: 604800000

notion:
  client-id: <NOTION_CLIENT_ID>
  client-secret: <NOTION_CLIENT_SECRET>
  redirect-uri: https://promate.ai.kr/api/notion/oauth/callback

ai:
  server:
    url: <AI_SERVER_URL>
```

### Docker로 실행

```bash
# Docker 이미지 빌드
docker build -t promate-back .

# 컨테이너 실행
docker run -d \
  -p 8080:8080 \
  --env-file .env \
  --name promate-back \
  promate-back
```

### 직접 실행

```bash
java -jar build/libs/Back-0.0.1-SNAPSHOT.jar
```

---

## 5. How to Test

### API 서버 상태 확인

```bash
curl https://api.promate.ai.kr/health
# 응답: 200 OK
```

### Swagger UI

서버 실행 후 아래 주소에서 API 문서 및 테스트 가능합니다.

```
http://localhost:8080/swagger-ui/index.html
```

### 주요 API 테스트

```bash
# 1. Google OAuth2 로그인 (브라우저에서 접속)
GET /oauth2/authorization/google

# 2. 프로젝트 생성
POST /api/projects
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
{
  "name": "테스트 프로젝트"
}

# 3. 프로젝트 목록 조회
GET /api/projects
Authorization: Bearer <JWT_TOKEN>

# 4. Notion OAuth 연동 콜백
GET /api/notion/oauth/callback?code=<NOTION_CODE>
```

### WebSocket 채팅 테스트

STOMP 클라이언트를 이용하여 아래 엔드포인트로 연결합니다.

```
ws://localhost:8080/ws-stomp
구독: /sub/chat/room/{projectId}
발행: /pub/chat/message
```

---

## 6. Description of Sample Data

### Database Schema

| 테이블 | 설명 |
|---|---|
| `users` | 사용자 정보 (이메일, 이름, Notion 토큰 등) |
| `projects` | 프로젝트 정보 (이름, 초대코드, AI 수집 데이터 등) |
| `project_members` | 프로젝트-사용자 관계 |
| `chat_messages` | 채팅 메시지 (TALK / SYSTEM / ENTER) |
| `project_session_summaries` | AI가 수집한 프로젝트 요약 정보 |

### 초기 데이터

별도의 초기 데이터(seed data)는 없으며, Google OAuth2 로그인을 통해 사용자 계정이 자동 생성됩니다.

---

## 7. Description of Used Open Source

| 라이브러리 | 버전 | 용도 |
|---|---|---|
| Spring Boot | 3.4.x | 웹 서버 프레임워크 |
| Spring Security | 6.4.x | 인증 및 인가 |
| Spring Data JPA | - | ORM |
| Spring WebSocket | - | 실시간 채팅 |
| jjwt | 0.12.x | JWT 토큰 처리 |
| MySQL Connector | 8.x | DB 연결 |
| Redis (Spring Data Redis) | - | 채팅 세션 캐싱 |
| Lombok | - | 코드 간소화 |
| Springdoc OpenAPI | 2.x | Swagger API 문서 |
| Docker | - | 컨테이너 배포 |
