# PR 리뷰 프로세스

## 리뷰 위임 방법

PR 생성 직후 `/codex:review` 플러그인으로 Codex에게 리뷰를 위임하고, Claude가 그 결과를 GitHub 인라인 코멘트로 포스팅한다.

### 리뷰 컨텍스트 (AGENTS.md로 Codex에게 자동 제공)

Codex는 `server/AGENTS.md`를 통해 아래 문서를 자동으로 참조한다:
- `docs/review-guide.md` — 전체 리뷰 체크리스트 (아키텍처, JPA, 보안, 성능, 트랜잭션, 테스트, 코드 품질)
- `docs/adr/아키텍처_및_코드_컨벤션.md` — ADR-002
- `docs/adr/예외_처리_전략.md` — ADR-004
- `docs/schema/ddl.sql` — 테이블명, 컬럼명, FK, 인덱스 기준

### 리뷰 실행

```
/codex:review --base main
```

Codex가 변경된 파일을 분석하고 이슈를 `파일경로:라인번호` 형태로 출력한다.

### GitHub 포스팅 방법

#### 인라인 코멘트 (파일·라인 지정)
```bash
COMMIT=$(git rev-parse HEAD)

gh api repos/hkjbrian/gongu/pulls/{PR번호}/comments \
  --method POST \
  -f body="{코멘트 내용}" \
  -f path="{파일경로}" \
  -F line={라인번호} \
  -f commit_id="$COMMIT"
```

#### 전체 리뷰 코멘트 (이슈 없음 / 종합 판정)
```bash
gh pr review {PR번호} --approve --body "리뷰 완료. 지적 사항 없음."
gh pr review {PR번호} --comment --body "{판정 결과 요약}"
```

### 포스팅 원칙
- 파일·라인이 특정되는 이슈 → 인라인 코멘트
- 전체 구조·아키텍처 이슈 → 전체 코멘트
- 이슈 없음 → `--approve`

---

## 리뷰 판정 규칙

### 1. 리뷰 내용 수집
```bash
gh pr view {PR번호} --comments
```

### 2. 이슈별 판정

| 판정 | 의미                                               |
|------|--------------------------------------------------|
| **수용 → 즉시 수정** | 지적이 타당하고, 수정 방법이 명확히 하나다                         |
| **수용 → 방법 탐색 후 논의** | 지적이 타당하지만, 구현 방법이 여러 가지라 사용자와 논의를 통해 설계 결정이 필요하다 |
| **거부 → 이유 제시** | 지적이 이 코드베이스 맥락에서 적절하지 않다                         |
| **보류 → 별도 이슈** | 타당하지만 현재 PR 범위를 벗어난다                             |

#### 판단 기준

**즉시 수정 가능한 경우** (방법이 하나로 수렴):
- public setter 제거, @Builder 접근 제한 등 컨벤션 위반
- 누락된 @Transactional 추가
- 명백한 테스트 케이스 누락
- 오타, 네이밍 개선

**방법 탐색이 필요한 경우** (설계 결정이 포함됨):
- **N+1 문제**: JOIN FETCH / @EntityGraph / @BatchSize / DTO Projection 등 트레이드오프 비교 필요
- **쿼리 최적화**: 인덱스 활용, 페이지네이션 전략 등
- **트랜잭션 구조 변경**: 경계 재설계가 필요한 경우
- **도메인 간 의존 구조 변경**: 레이어 재설계가 수반되는 경우
- **외부 라이브러리 도입**: QueryDSL, Spring Batch 등
- 등 리뷰에서 지적된 사항이 다양한 방법으로 해결될 수 있는 경우를 말한다.

방법 탐색 시 Claude가 직접 각 선택지의 장단점을 분석하고 **권장안과 근거**를 사용자에게 제시한다.

### 3. 사용자에게 판정 요약 제시 (형식)

```
## PR #{번호} 리뷰 판정

### 🔴 Critical
1. [지적 내용 요약]
   → 판정: 수용 → 즉시 수정
   → 이유: (판단 근거 — 방법이 하나로 수렴하는 이유)

2. [지적 내용 요약]
   → 판정: 수용 → 방법 탐색 후 논의
   → 선택지:
     - A. JOIN FETCH — 단일 쿼리, countQuery 별도 필요, ManyToOne에 적합
     - B. @BatchSize — 코드 변경 최소, 2쿼리, Collection에도 안전
     - C. DTO Projection — 성능 최우선, 엔티티 추적 불필요 시
   → Claude 권장: A (이유: 이 케이스는 ManyToOne이고 필터 조건을 DB로 내려야 해서)

### 🟡 Minor
3. [지적 내용 요약]
   → 판정: 거부
   → 이유: (이 프로젝트에서 해당 지적이 적합하지 않은 이유)

진행할까요? 이견 있으면 말씀해주세요.
```

### 4. 합의 후 실행

사용자가 확인하면:
1. **각 리뷰 코멘트에 판정 결과를 reply로 달기** (아래 형식 참고)
2. 수정 사항 구현 (Codex에게 다시 위임한다.)
3. `./gradlew compileJava` 및 관련 테스트 통과 확인
4. 커밋: `fix: {수정 내용} (#이슈번호)`
5. push
6. PR에 종합 판정 결과 코멘트 게시

### 4-1. 리뷰 코멘트에 판정 reply 달기

각 Codex 리뷰 코멘트(인라인/전체 모두)에 아래 형식으로 reply를 달아 의사결정을 추적한다.

**reply 형식:**
```
[수용 → 즉시 수정] {구체적인 해결 방안}
→ 이유: {판정 근거}
```
```
[수용 → 방법 탐색] {채택한 선택지 및 구체적 해결 방안}
→ 이유: {선택 근거}
```
```
[거부] {현재 코드를 유지하는 이유}
→ 이유: {ADR 조항 또는 프로젝트 맥락 기준}
```
```
[보류 → 이슈 등록] #{새 이슈 번호}로 별도 추적
→ 이유: {현재 PR 범위를 벗어나는 이유}
```

**인라인 코멘트에 reply 달기:**
```bash
gh api repos/hkjbrian/gongu/pulls/{PR번호}/comments/{comment_id}/replies \
  --method POST \
  -f body="{reply 내용}"
```

**전체 코멘트(issue comment)에 reply 달기:**
```bash
gh api repos/hkjbrian/gongu/issues/{PR번호}/comments/{comment_id}/replies \
  --method POST \
  -f body="{reply 내용}"
```

### 4-2. 최종 approve 시 리뷰 스레드 resolve

모든 판정이 끝나고 최종 approve 전에 열려 있는 리뷰 스레드를 모두 resolve한다.

```bash
# 1. PR의 review thread ID 목록 조회
gh api graphql -f query='
  query {
    repository(owner: "hkjbrian", name: "gongu") {
      pullRequest(number: {PR번호}) {
        reviewThreads(first: 50) {
          nodes { id isResolved }
        }
      }
    }
  }
'

# 2. 미해결 스레드 각각 resolve
gh api graphql -f query='
  mutation {
    resolveReviewThread(input: {threadId: "{threadId}"}) {
      thread { isResolved }
    }
  }
'
```

resolve 후 approve:
```bash
gh pr review {PR번호} --approve --body "모든 리뷰 항목 처리 완료."
```

### 5. 판정 결과 코멘트 형식

```markdown
## 리뷰 판정 결과

### 🔴 Critical
**[수용 → 수정 완료] {지적 제목}**
채택한 방법: {선택지} — {선택 이유}
{수정한 내용 요약} (커밋 `{해시}`)

### 🟡 Minor
**[거부] {지적 제목}**
{거부 근거 — ADR 조항 또는 프로젝트 맥락 기준}

**[보류 → 이슈 등록] {지적 제목}**
현재 PR 범위를 벗어남. #{새 이슈 번호}로 별도 추적.
```

판정 종류: `수용 → 수정 완료` / `수용 → 방법 탐색 후 수정` / `거부` / `보류 → 이슈 등록`

### 판정 원칙

- **ADR 위반 지적**은 원칙적으로 수용한다. ADR 변경이 필요한 경우 먼저 사용자와 논의한다.
- **성능·구조 관련 지적**은 반드시 대안 탐색 후 선택지를 제시한다. Codex가 제안한 방법을 그대로 수용하지 않는다.
- **도구·라이브러리 추가** 지적은 ADR 작성 후 별도 이슈로 처리한다.
- **"더 나은 패턴" 제안**은 현재 코드베이스 전반에 영향을 주는지 확인 후 수용 여부 결정한다.
- **테스트 커버리지 지적**은 핵심 비즈니스 규칙이면 즉시 수용, 단순 getter 수준이면 Minor 보류.
