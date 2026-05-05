# Gongu 프로젝트 - Claude 행동 지침

## 역할 분리

- **Claude**: 설계 결정, 작업 계획, 코드 검증, GitHub 관리, 조율
- **Codex (Agent)**: 실제 코드 구현. Claude는 직접 코드를 작성하지 않고 반드시 Codex에게 위임한다.

---

## 작업 흐름

새 작업을 시작할 때 반드시 아래 순서를 따른다.

```
1. GitHub Issue 확인 (gh issue view)
2. main 브랜치 최신화 (git pull)
3. 이슈 브랜치 생성 (CONTRIBUTING.md 컨벤션)
4. Codex에게 구현 위임 (Agent 도구)
5. 생성된 코드 내용 확인
6. 빌드 검증 (./gradlew compileJava)
7. 커밋 (Co-Authored-By 없이)
8. push → PR 생성 (gh pr create)
9. Codex에게 PR 리뷰 위임 (Agent 도구) → PR 코멘트 자동 포스팅
```

---

## GitHub 관리 규칙

### 브랜치명
`{type}/#{이슈번호}-{짧은-설명}` (CONTRIBUTING.md 기준)

예: `feat/#10-base-entity`, `chore/#8-add-dependencies`

### 커밋 메시지
- `Co-Authored-By` 절대 포함하지 않는다.
- 형식: `type: 작업 내용 (#이슈번호)`

### PR
- 제목: `[TYPE] 작업 내용 (#이슈번호)`
- 본문: PR 템플릿 (Issue ID / 작업 내용 / 참고사항)
- `close #이슈번호` 본문에 포함
- Milestone 반드시 연결

### GitHub CLI
- Milestone 생성/조회: `gh api repos/{owner}/{repo}/milestones`
- Issue 생성/수정: `gh issue create`, `gh issue edit`
- PR 생성: `gh pr create`

---

## 커밋 전 체크리스트

- [ ] `./gradlew compileJava` 성공
- [ ] 민감 파일 미포함 (`*.env`, `application-local.yml` 등)
- [ ] `.gitignore`에 민감 파일 등록 여부 확인
- [ ] 이번 이슈와 무관한 파일 미포함
- [ ] **엔티티 포함 시**: `@Table(name=...)`, `@Column(name=...)`, `@JoinColumn(name=...)`을 `docs/schema/ddl.sql`과 직접 대조

## 커밋 단위 규칙

하나의 이슈를 하나의 커밋으로 올리지 않는다. 논리적 단위로 나누어 커밋한다.

예시:
- 엔티티 이슈: (1) 엔티티 클래스, (2) 리포지토리
- Security 이슈: (1) SecurityConfig, (2) JwtAuthenticationFilter
- 서비스 이슈: (1) 도메인 로직, (2) 서비스 레이어, (3) 컨트롤러+DTO

---

## 설계 원칙

### 구현 전 설계 우선
- 기술적 결정이 필요한 경우 충분히 논의 후 ADR 문서 작성 → 구현 시작
- ADR 경로: `server/docs/adr/`

### ADR 작성 기준
구현 전 ADR이 필요한 상황:
- 라이브러리/프레임워크 선택
- 아키텍처 패턴 결정
- 코딩 룰 제정
- 인프라 설계

### 문서 경로
모든 문서는 `server/docs/` 하위에서 관리한다.
- `server/docs/adr/` — Architecture Decision Records
- `server/docs/api/` — OpenAPI 스펙
- `server/docs/schema/` — DDL, 테이블 정의서

---

## Codex 위임 규칙

### 위임 전 Claude가 먼저 확인·결정할 것

엔티티/리포지토리 작업이 포함된 경우:
- `docs/schema/ddl.sql`을 직접 읽어 테이블명·컬럼명·NULL 여부·인덱스를 파악한다.
- 소프트 딜리트 불변식을 결정하고 프롬프트에 명시한다.  
  예) "`deletedAt IS NULL`이 삭제 여부의 권위 있는 신호. `isActive`는 서비스 레벨 비활성화 전용."
- 비즈니스 정책이 필요한 사항(이메일 재사용 허용 여부, 멱등성 요구사항 등)은 위임 전에 결정한다.

### 위임 시 프롬프트에 반드시 포함할 것
1. 구현할 파일의 정확한 경로
2. 관련 기존 파일 경로 (읽어서 참고하도록)
3. **엔티티 포함 시**: `docs/schema/ddl.sql` 경로를 명시하고 테이블명·컬럼명을 DDL 기준으로 맞추도록 지시
4. 적용해야 할 ADR/컨벤션 기준
5. 소프트 딜리트 불변식 및 결정된 비즈니스 정책
6. 금지 사항 (기존 파일 수정 금지 등)
7. 프로젝트 기본 정보 (패키지명, Spring Boot 버전, Java 버전)

### 위임하지 않는 것
- GitHub 관리 (Issue/PR/Milestone): Claude가 직접 처리
- 커밋/push: Claude가 직접 처리
- 설계 결정 및 ADR 작성: Claude가 직접 처리

---

## PR 리뷰 위임 규칙

PR 생성 직후 반드시 Codex에게 독립 리뷰를 위임한다.

### 위임 시 Codex에게 전달할 것
1. PR 번호
2. 리포지토리 경로 (`/Users/hankyungjun/projects/gongu/server`)
3. 변경된 파일 목록
4. 아래 문서를 직접 읽어 리뷰 기준으로 삼도록 지시

### Codex가 반드시 읽어야 할 문서
- `docs/review-guide.md` — 전체 리뷰 체크리스트 (아키텍처, JPA, 보안, 성능, 트랜잭션, 테스트, 코드 품질)
- `docs/adr/아키텍처_및_코드_컨벤션.md` — ADR-002: Rich Domain Model, Entity 생성 규칙, DTO 변환 규칙
- `docs/adr/예외_처리_전략.md` — ADR-004: 예외 계층, ErrorCode 형식, 레이어별 예외 처리 원칙
- `docs/schema/ddl.sql` — 테이블명, 컬럼명, FK, 인덱스 기준 (JPA 매핑 검증용)

### 리뷰 프롬프트 템플릿

```
당신은 Spring Boot 코드 리뷰어입니다. 아래 순서대로 리뷰를 수행하세요.

1. 다음 문서를 직접 읽어 리뷰 기준으로 삼으세요:
   - docs/review-guide.md
   - docs/adr/아키텍처_및_코드_컨벤션.md
   - docs/adr/예외_처리_전략.md
   - docs/schema/ddl.sql

2. 변경된 파일을 모두 읽으세요: {파일 목록}

3. review-guide.md의 8개 섹션을 기준으로 각 항목을 점검하세요.

4. 발견된 이슈를 Critical / Minor 로 구분하여 정리하세요.
   - Critical: 아키텍처 규칙 위반, 보안 취약점, 트랜잭션 누락, 핵심 비즈니스 테스트 누락, N+1
   - Minor: 네이밍, 불필요한 복잡도, 테스트 DisplayName 등

5. 결과를 GitHub에 올리세요:
   - Critical 이슈 있음: gh pr review {번호} --comment --body "..."
   - Minor만 있음:      gh pr review {번호} --comment --body "..."
   - 이슈 없음:         gh pr review {번호} --approve --body "리뷰 완료. 지적 사항 없음."
```

### Codex가 리뷰 결과를 올리는 방법
```bash
gh pr review {PR번호} --comment --body "{리뷰 내용}"
```
문제가 없으면: `gh pr review {PR번호} --approve --body "리뷰 완료. 지적 사항 없음."`

---

## 프로젝트 기본 정보

- **베이스 패키지**: `com.gongu.server`
- **소스 루트**: `server/src/main/java/com/gongu/server/`
- **기술 스택**: Spring Boot 3.5, Java 25, MySQL 8.0, Redis 7.4
- **빌드**: `./gradlew` (서버 디렉터리 기준)
- **GitHub 저장소**: `hkjbrian/gongu`
- **패키지 구조**: `domain/{auth,user,store,product,order,payment,notification}`, `global/{common,exception,config,security}`
- **아키텍처**: 3-Layered + Rich Domain Model (ADR-002 참고)

---

## 현재 Milestone 진행 상황

| Milestone | 번호 | 상태 |
|-----------|------|----|
| 공통 기반 | #2 | 완료 |
| 인증/인가 | #3 | 대기 |
| 매장/관리자 도메인 | #4 | 대기 |
| 상품 도메인 | #5 | 대기 |
| 주문 도메인 | #6 | 대기 |
| 결제 도메인 | #7 | 대기 |
| 알림 도메인 | #8 | 대기 |
