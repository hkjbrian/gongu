# Gongu Project — Codex Agent Instructions

## 프로젝트 기본 정보

- Spring Boot 3.5, Java 25, MySQL 8.0, Redis 7.4
- 베이스 패키지: `com.gongu.server`
- 소스 루트: `src/main/java/com/gongu/server/`
- 아키텍처: 3-Layered + Rich Domain Model (ADR-002)
- 패키지 구조: `domain/{auth,user,store,product,order,payment,notification}`, `global/{common,exception,config,security}`

---

## 코드 리뷰어로 호출됐을 때

리뷰 시작 전 아래 순서로 컨텍스트를 수집한다:

1. **PR 내용 확인** — 무엇을 왜 변경했는지 파악
   ```bash
   gh pr view {PR번호}
   ```

2. **연관 Issue 확인** — PR 본문의 `close #번호`에서 이슈 번호를 찾아 읽는다
   ```bash
   gh issue view {이슈번호}
   ```

3. **관련 문서 탐색** — `docs/` 하위에서 이슈·변경 내용과 연관된 문서를 찾아 읽는다
   (ADR, spec, note 등 — 구현 의도와 설계 결정 파악 목적)

4. **리뷰 기준 문서 읽기** — 아래를 반드시 읽는다
   - `docs/review-guide.md` — 전체 리뷰 체크리스트
   - `docs/adr/아키텍처_및_코드_컨벤션.md` — ADR-002
   - `docs/adr/예외_처리_전략.md` — ADR-004
   - `docs/schema/ddl.sql` — 테이블명·컬럼명·FK·인덱스 기준

### 출력 형식

- 이슈를 **Critical / Minor** 로 분류한다
- 각 이슈마다 반드시 **`파일경로:라인번호`** 를 포함한다
  (Claude가 이 정보를 파싱해서 GitHub 인라인 코멘트로 포스팅한다)

### 하지 말아야 할 것

- 코드를 직접 수정하지 않는다
- GitHub에 직접 포스팅하지 않는다 (포스팅은 Claude가 담당)
