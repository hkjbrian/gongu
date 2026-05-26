# PR 리뷰 프로세스

## 전체 리뷰 흐름 (순서)

```
[1] codex review 실행 → Codex가 파일경로:라인번호 형태로 이슈 출력
[2] Claude가 Codex 결과를 GitHub 인라인 코멘트로 포스팅
[3] Claude가 모든 코멘트를 수집하여 이슈별 판정 수행
[4] Claude가 판정 요약을 사용자에게 제시 → 합의
[5] 합의 후 각 thread에 판정 reply → Codex 수정 위임 → /codex:review 재실행
    → 새 이슈 없으면 approve / 새 이슈 있으면 [3]~[5] 반복
```

> **이 문서는 리뷰 작업 시작 전 반드시 전체를 읽어야 한다. CLAUDE.md의 하드 게이트 규칙과 함께 적용된다.**

---

## 리뷰 위임 방법

### 리뷰 컨텍스트 (AGENTS.md로 Codex에게 자동 제공)

Codex는 `server/AGENTS.md`를 통해 아래 문서를 자동으로 참조한다:
- `docs/review-guide.md` — 전체 리뷰 체크리스트 (아키텍처, JPA, 보안, 성능, 트랜잭션, 테스트, 코드 품질)
- `docs/adr/아키텍처_및_코드_컨벤션.md` — ADR-002
- `docs/adr/예외_처리_전략.md` — ADR-004
- `docs/schema/ddl.sql` — 테이블명, 컬럼명, FK, 인덱스 기준

### [1] 리뷰 실행

```bash
/codex:review --base main
```

Codex가 변경된 파일을 분석하고 이슈를 `파일경로:라인번호` 형태로 출력한다.

---

> ⛔ **STOP — [1] → [2]**
> Codex 결과가 나왔다고 바로 판정하지 않는다.
> **[2] 인라인 코멘트 포스팅이 완료되기 전까지 [3] 판정을 시작하지 않는다.**

---

### [2] GitHub 포스팅 방법

#### Finding이 없을 때
[3]~[4] 생략하고 바로 [5-2] resolve 후 approve한다.
```bash
gh pr review {PR번호} --approve --body "리뷰 완료. 지적 사항 없음."
```

#### 인라인 코멘트 body 형식
```markdown
## [severity] {finding 제목}

**파일**: `{파일경로}` L{라인번호}

{Codex finding 내용 — 문제 상황 설명}

**권장 수정 방향**: {Codex 권장사항}
```

#### 인라인 코멘트 포스팅 (변경된 라인)
```bash
COMMIT=$(git rev-parse HEAD)

gh api repos/hkjbrian/gongu/pulls/{PR번호}/comments \
  --method POST \
  -f body="{위 형식의 코멘트 내용}" \
  -f path="{파일경로}" \
  -F line={라인번호} \
  -f commit_id="$COMMIT"
```

#### diff 외 라인 처리 (변경되지 않은 기존 라인)
GitHub 인라인 코멘트 API는 diff에 포함된 라인에만 달 수 있다.
변경되지 않은 기존 라인에 finding이 있을 경우 PR 전체 코멘트로 대신 포스팅한다.
```bash
gh pr comment {PR번호} --body "{위 형식의 코멘트 내용}

> ⚠️ 이 finding은 이번 PR에서 변경되지 않은 기존 라인(`{파일경로}` L{라인번호})에 해당하여 PR 코멘트로 포스팅합니다."
```

### 포스팅 원칙
- 변경된 라인의 finding → 인라인 코멘트 (`gh api .../pulls/{PR}/comments`)
- 변경되지 않은 기존 라인의 finding → PR 전체 코멘트 (파일경로·라인번호 명시)
- 전체 구조·아키텍처 이슈 → PR 전체 코멘트
- Finding 없음 → `--approve` 후 종료

---

> ⛔ **STOP — [2] → [3]**
> 모든 finding의 인라인 코멘트 포스팅이 완료된 후에만 판정을 시작한다.
> **판정은 반드시 fresh subagent에게 위임한다. 구현에 참여한 Claude가 직접 판정하지 않는다.**

---

## 리뷰 판정 규칙

### Severity 매핑

Codex finding의 severity를 판정 요약 표시로 변환한다.

| Codex | 판정 요약 표시 | 의미 |
|-------|-------------|------|
| `high` | 🔴 Critical | 머지 차단 — 반드시 수정 또는 명시적 거부 근거 필요 |
| `medium` | 🟡 Minor | 수정 권장 — 판정 후 결정 |
| `low` | 🔵 Info | 참고용 — 거부 가능, 필요 시 별도 이슈 |

### [3] 이슈 판정

**원칙: 판정은 반드시 fresh subagent에게 위임한다.**
구현 과정에 참여한 Claude가 판정하면 자신이 내린 설계 결정을 방어하는 방향으로 판단이 기울 수 있다.

**subagent에게 줄 것: 경로와 명령어만. 내용은 subagent가 직접 수집한다.**
Claude가 파일 내용을 요약·발췌해서 프롬프트에 넣으면 Claude의 필터를 통과한 정보만 전달되어 객관성이 훼손된다.

#### subagent 프롬프트 템플릿

```text
PR #{PR번호} 리뷰 판정을 수행하세요.

아래 명령어로 필요한 정보를 직접 수집하세요:

# PR diff
gh pr diff {PR번호}

# 인라인 리뷰 코멘트
gh api repos/hkjbrian/gongu/pulls/{PR번호}/comments --jq '.[] | {id, user: .user.login, path, line, body}'

# 전체 리뷰 (CodeRabbit 등 포함)
gh api repos/hkjbrian/gongu/pulls/{PR번호}/reviews --jq '.[] | {id, user: .user.login, state, body}'

# 판정 기준 전체
cat .claude/review-process.md

# 이슈 스펙 전체
cat docs/superpowers/specs/{관련_스펙_파일}.md

# 관련 ADR
cat docs/adr/아키텍처_및_코드_컨벤션.md

수집한 내용을 바탕으로 review-process.md의 [3] 이슈별 판정 기준에 따라 각 이슈를 판정하고,
[4] 사용자에게 판정 요약 제시 형식으로 출력하세요.
```

#### 이슈별 판정

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
- **Service/Domain 단위 테스트 누락** → Critical, 즉시 수정
- 오타, 네이밍 개선

**방법 탐색이 필요한 경우** (설계 결정이 포함됨):
- **N+1 문제**: JOIN FETCH / @EntityGraph / @BatchSize / DTO Projection 등 트레이드오프 비교 필요
- **쿼리 최적화**: 인덱스 활용, 페이지네이션 전략 등
- **트랜잭션 구조 변경**: 경계 재설계가 필요한 경우
- **도메인 간 의존 구조 변경**: 레이어 재설계가 수반되는 경우
- **외부 라이브러리 도입**: QueryDSL, Spring Batch 등
- 등 리뷰에서 지적된 사항이 다양한 방법으로 해결될 수 있는 경우를 말한다.

방법 탐색 시 Claude가 직접 각 선택지의 장단점을 분석하고 **권장안과 근거**를 사용자에게 제시한다.

---

> ⛔ **STOP — [3] → [4]**
> 판정 결과를 GitHub에 먼저 올리지 않는다.
> **[4] 사용자에게 판정 요약을 제시하고 명시적 합의를 받기 전까지 GitHub에 아무것도 달지 않는다.**

---

### [4] 사용자에게 판정 요약 제시 (형식)

```markdown
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

---

> ⛔ **STOP — [4] → [5]**
> 사용자가 판정 요약을 확인하고 명시적으로 동의한 후에만 [5]를 진행한다.
> **"진행할까요?" 물음에 대한 응답이 있어야 한다. 침묵·맥락 추정으로 합의를 가정하지 않는다.**

---

### [5] 합의 후 실행

사용자가 확인하면:
1. **각 인라인 리뷰 코멘트 thread에 판정 결과를 직접 reply로 달기** ([5-1] 참고)
2. 수정 사항 구현 (Codex에게 다시 위임한다.)
3. `./gradlew compileJava` 및 관련 테스트 통과 확인
4. 커밋: `fix: {수정 내용} (#이슈번호)`
5. push
6. `/codex:review --base main` 재실행
7. 새 이슈 없음 → [5-2] approve 진행 / 새 이슈 있음 → [3]~[5] 반복

### [5-1] 각 리뷰 thread에 판정 reply 달기

**핵심 원칙: 판정 결과를 하나의 종합 코멘트로 묶지 않는다. 각 thread에 직접 reply를 달아 맥락을 보존한다.**

> ⛔ **STOP — 종합 코멘트 금지**
> `gh pr comment`로 판정 내용을 하나의 코멘트에 묶어 올리는 것은 금지된다.
> **반드시 각 인라인 코멘트 thread에 개별 reply(`/pulls/.../comments/{id}/replies`)를 달아야 한다.**

#### comment_id 수집
```bash
gh api repos/hkjbrian/gongu/pulls/{PR번호}/comments \
  --jq '.[] | {id: .id, path: .path, body: (.body[:60])}'
```

#### 각 thread에 reply
```bash
gh api repos/hkjbrian/gongu/pulls/{PR번호}/comments/{comment_id}/replies \
  --method POST \
  -f body="{reply 내용}"
```

> PR review comment(인라인)는 `/pulls/.../comments/{id}/replies`로 reply 가능.
> 일반 issue comment는 replies 엔드포인트가 없으므로 `/issues/{PR번호}/comments`로 새 comment를 달아야 한다.

#### reply 형식
```text
[수용 → 즉시 수정] {구체적인 해결 방안}
→ 이유: {판정 근거}
커밋: {hash}
```
```text
[수용 → 방법 탐색] {채택한 선택지 및 구체적 해결 방안}
→ 이유: {선택 근거}
커밋: {hash}
```
```text
[거부] {현재 코드를 유지하는 이유}
→ 이유: {ADR 조항 또는 프로젝트 맥락 기준}
```
```text
[보류 → 이슈 등록] #{새 이슈 번호}로 별도 추적
→ 이유: {현재 PR 범위를 벗어나는 이유}
```

### [5-2] 최종 approve 순서

모든 판정·수정이 끝난 뒤 아래 순서를 지킨다.

```
1. [5-3] 판정 결과 종합 코멘트 포스팅
2. 열려 있는 리뷰 스레드 resolve
3. --approve
```

#### 스레드 resolve
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

#### Approve
```bash
gh pr review {PR번호} --approve --body "모든 리뷰 항목 처리 완료."
```

### [5-3] 판정 결과 코멘트 형식

> **[5-3]은 [5-2] approve 직전에 PR에 포스팅한다. approve 메시지와 별개로 반드시 달아야 한다.**

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
- **테스트 누락 지적**: Service/Domain 단위 테스트 누락은 Critical 수용. 슬라이스/E2E 테스트 누락은 상황별 기준(codex-delegation.md)을 적용해 판정한다.
