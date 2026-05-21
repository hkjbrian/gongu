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
  - 코드 리뷰: `/review` 플러그인 호출 → 결과를 Claude가 수신 후 GitHub에 포스팅

---

## 작업 흐름 요약

자세한 12단계 흐름 → [`.claude/workflow.md`](.claude/workflow.md)

```
1. GitHub Issue 확인  →  2. main 최신화  →  3. 이슈 브랜치 생성
4. writing-plans 스킬로 계획 수립  →  5. Codex에 구현 위임
6. 빌드 검증  →  7. 커밋  →  8. push + PR 생성
9. /review로 리뷰 위임  →  10. Claude 판정  →  11. 완료 알림
12. PR merge는 사용자가 직접
```

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

## 상세 규칙 문서

| 상황 | 참조 파일 |
|------|----------|
| 커밋/브랜치/PR 규칙 | [`.claude/github-rules.md`](.claude/github-rules.md) |
| Codex 위임 방법 | [`.claude/codex-delegation.md`](.claude/codex-delegation.md) |
| PR 리뷰 + 판정 규칙 | [`.claude/review-process.md`](.claude/review-process.md) |
| 전체 작업 흐름 | [`.claude/workflow.md`](.claude/workflow.md) |
