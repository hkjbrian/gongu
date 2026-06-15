# Server Containerization for Load Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 부하테스트 환경의 colocation bias를 줄이기 위해 Spring Boot 서버를 Docker 컨테이너(2 vCPU / 2GB RAM 제한)로 실행할 수 있도록 Dockerfile 및 docker-compose.yml을 구성한다.

**Spec:** GitHub Issue #154

**Tech Stack:** Docker, Docker Compose, Spring Boot 3.5, Java 25, Gradle

---

## 변경 파일 맵

| 파일 | 작업 |
|------|------|
| `Dockerfile` | **신규 생성** — 멀티스테이지 빌드 |
| `docker-compose.yml` | **수정** — server 서비스 추가, mock-pg URL 변경 |
| `src/main/resources/application-perf.yml` | **수정** — portone base-url 컨테이너 서비스명으로 변경 |

**읽기 전용 참조 파일:**
- `build.gradle` — Java 25, Spring Boot 3.5 버전 확인
- `src/main/resources/application.yml` — 환경변수 패턴(`${MYSQL_HOST:localhost}` 등) 참조
- `docker-compose.yml` — 현재 서비스 구조, 네트워크명(`gongu-net`) 확인

---

### Task 1: Dockerfile 생성

**참고 문서/파일 (읽어야 할 것):**
- `build.gradle` — Java toolchain 버전(25), 빌드 플러그인 확인
- `src/main/resources/application.yml` — 환경변수명 패턴 확인

**수정 대상 파일:**
- Create: `Dockerfile` (프로젝트 루트, `build.gradle`과 같은 위치)

**금지 사항:**
- `docker-compose.yml`, `application-perf.yml` — 이 태스크에서 건드리지 않음

**구현 방향:**
- 멀티스테이지 빌드 사용: build stage + runtime stage 분리
- Build stage: `eclipse-temurin:25-jdk` 또는 `eclipse-temurin:21-jdk`(25 없으면) 이미지 사용, `/workspace`에서 `./gradlew bootJar -x test` 실행
- Runtime stage: `eclipse-temurin:25-jre` 또는 `-jre` 슬림 이미지 사용, `build/libs/*.jar` 복사
- `SPRING_PROFILES_ACTIVE` 환경변수를 `perf`로 기본 설정 (`ENV SPRING_PROFILES_ACTIVE=perf`)
- 컨테이너 포트 `8080` EXPOSE
- `ENTRYPOINT ["java", "-jar", "/app/app.jar"]`
- `.gradlew`에 실행 권한이 없을 수 있으므로 build stage에서 `RUN chmod +x gradlew` 추가

**검증:**
```bash
docker build -t gongu-server:test .
```
Expected: 빌드 성공, 이미지 `gongu-server:test` 생성 확인 (`docker images | grep gongu-server`)

**커밋:**
```bash
git add Dockerfile
git commit -m "chore: Spring Boot 멀티스테이지 Dockerfile 추가 (#154)"
```

---

### Task 2: docker-compose.yml — server 서비스 추가 및 mock-pg URL 수정

**참고 문서/파일 (읽어야 할 것):**
- `docker-compose.yml` — 현재 네트워크명(`gongu-net`), 기존 서비스 구조, mysql healthcheck 패턴
- `src/main/resources/application.yml` — 환경변수명 목록 (`MYSQL_HOST`, `REDIS_HOST`, `JWT_SECRET` 등)

**수정 대상 파일:**
- Modify: `docker-compose.yml`

**금지 사항:**
- `redis`, `mysql`, `prometheus`, `grafana` 서비스 설정 변경 금지
- `application-perf.yml` — 다음 태스크에서 처리

**구현 방향:**

1. `services`에 `server` 서비스 추가:
   - `container_name: gongu-server`
   - `build: .` (루트 Dockerfile 사용)
   - 리소스 제한: `deploy.resources.limits.cpus: '2'`, `deploy.resources.limits.memory: 2g`
   - `ports: - "${SERVER_PORT:-8080}:8080"`
   - `environment`에 아래 환경변수 주입 (`.env` 파일 또는 환경변수로 관리):
     ```
     SPRING_PROFILES_ACTIVE=perf
     MYSQL_HOST=mysql
     MYSQL_PORT=3306
     MYSQL_DATABASE=${MYSQL_DATABASE}
     MYSQL_USERNAME=${MYSQL_USERNAME}
     MYSQL_PASSWORD=${MYSQL_PASSWORD}
     REDIS_HOST=redis
     REDIS_PORT=6379
     JWT_SECRET=${JWT_SECRET}
     PORTONE_API_SECRET=${PORTONE_API_SECRET}
     CORS_ALLOWED_ORIGINS=${CORS_ALLOWED_ORIGINS:-http://localhost:3000}
     ```
   - `depends_on`: `mysql` (condition: `service_healthy`), `redis`
   - `networks: - gongu-net`

2. `mock-pg` 서비스의 `SERVER_WEBHOOK_URL` 변경:
   - 기존: `http://host.docker.internal:8080/payments/webhook`
   - 변경: `http://gongu-server:8080/payments/webhook`
   - `depends_on`에 `server` 추가

**검증:**
```bash
docker compose config
```
Expected: YAML 파싱 오류 없음, server 서비스가 출력에 포함됨

**커밋:**
```bash
git add docker-compose.yml
git commit -m "chore: docker-compose에 server 서비스 추가 및 mock-pg URL 수정 (#154)"
```

---

### Task 3: application-perf.yml — portone base-url 컨테이너 서비스명으로 변경

**참고 문서/파일 (읽어야 할 것):**
- `src/main/resources/application-perf.yml` — 현재 `portone.base-url` 값 확인
- `docker-compose.yml` — mock-pg 서비스명 확인 (`gongu-mock-pg`)

**수정 대상 파일:**
- Modify: `src/main/resources/application-perf.yml`

**금지 사항:**
- `src/main/resources/application.yml` — 수정 금지 (공통 설정)
- `src/main/resources/application-local.yml` — 수정 금지 (로컬 IntelliJ 설정)

**구현 방향:**
- `portone.base-url` 값을 `http://localhost:8090` → `http://gongu-mock-pg:8090` 으로 변경
- perf 프로파일은 컨테이너 환경 전용이므로 컨테이너 서비스명을 직접 사용

**검증:**
```bash
docker compose up -d mysql redis mock-pg server
docker compose logs server --tail=50
```
Expected: `Started GonguServerApplication`로그 확인, 8080 포트 응답

최종 통합 검증:
```bash
k6 run --env BASE_URL=http://localhost:8080 load-test/scenarios/01-inventory-concurrency.js
```
Expected: k6 시나리오가 컨테이너 서버 대상으로 정상 실행됨 (HTTP 오류 없이 setup 완료)

**커밋:**
```bash
git add src/main/resources/application-perf.yml
git commit -m "chore: perf 프로파일 portone base-url 컨테이너 서비스명으로 변경 (#154)"
```

---

## 완료 후

PR 생성 후 `CLAUDE.md` 9~11단계 (Codex 리뷰 위임 → 판정 → 반영) 따름.
