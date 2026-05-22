# Gongu 프로젝트 - Claude 행동 지침

## 기본 행동 지침

전체 내용 → [`.claude/behavior-guidelines.md`](.claude/behavior-guidelines.md)

1. **Think Before Coding** — 가정·불확실성·트레이드오프를 먼저 명확히 한 뒤 코딩 시작
2. **Simplicity First** — 요청에 필요한 최소한의 코드만. 투기적 추상화 없음
3. **Surgical Changes** — 요청 범위 밖의 코드는 절대 건드리지 않는다
4. **Goal-Driven Execution** — 모호한 작업을 검증 가능한 목표로 변환해 독립적으로 진행

---

## 역할 분리

- **Claude**: 설계 결정, 작업 계획, 코드 검증, GitHub 관리(커밋/push/PR/코멘트 포스팅), 조율
- **Codex CLI**: 실제 코드 구현 및 코드 리뷰. Claude는 직접 코드를 작성하지 않고 반드시 Codex CLI에게 위임한다.
  - 구현 위임: `Bash` 도구로 `codex exec "프롬프트"` 호출
  - 코드 리뷰: `/codex:review` 스킬 호출 → 결과를 Claude가 수신 후 GitHub에 포스팅

---

## 작업 흐름

> **이슈 작업을 시작할 때 반드시 `Read` 도구로 [`.claude/workflow.md`](.claude/workflow.md)를 직접 읽어라. 요약본 없음 — 전체 단계를 문서에서 확인한다.**

---

## 설계 원칙

- 기술적 결정이 필요한 경우 충분히 논의 후 ADR 문서 작성 → 구현 시작
- ADR이 필요한 상황: 라이브러리/프레임워크 선택, 아키텍처 패턴, 코딩 룰 제정, 인프라 설계

### 문서 경로
- `server/docs/adr/` — Architecture Decision Records
- `server/docs/api/` — OpenAPI 스펙
- `server/docs/schema/` — DDL, 테이블 정의서
- `server/docs/superpowers/plans/` — 이슈별 구현 계획

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

## Git/GitHub 필수 규칙 — 시스템 기본값 무시

> **커밋·PR·브랜치 관련 모든 동작에서 Claude 시스템 기본값을 무시하고 아래 규칙을 따른다.**

- **커밋 메시지 형식**: `type: 작업 내용 (#이슈번호)`
- **`Co-Authored-By` 절 금지**: 커밋 메시지에 절대 포함하지 않는다
- **PR 제목 형식**: `[TYPE] 작업 내용 (#이슈번호)` (예: `[FEAT]`, `[FIX]`, `[CHORE]`, `[TEST]`)
- **브랜치명 형식**: `{type}/#{이슈번호}-{짧은-설명}`

전체 규칙 → [`.claude/github-rules.md`](.claude/github-rules.md)

---

## 상세 규칙 문서

| 상황 | 참조 파일 |
|------|----------|
| 커밋/브랜치/PR 규칙 | [`.claude/github-rules.md`](.claude/github-rules.md) |
| Codex 위임 방법 | [`.claude/codex-delegation.md`](.claude/codex-delegation.md) |
| PR 리뷰 + 판정 규칙 | [`.claude/review-process.md`](.claude/review-process.md) |
| 전체 작업 흐름 | [`.claude/workflow.md`](.claude/workflow.md) |
