# 에이전트 기반 자동 개선 파이프라인 설계 (#237)

## 1. 목적

- **에이전트 A (기획/평가자)**: 백엔드 개발자 포트폴리오 관점에서 기존 기능의 허점·미완 마일스톤을 찾아 이슈를 *제안*한다.
- **에이전트 B (개발자)**: 승인된 이슈의 타당성을 판별하고, 계획을 세우고, 승인되면 구현 → PR → 리뷰 루프까지 진행한다.
- **사람**: 3개 게이트(이슈 승인 · 계획 승인 · 머지)에서만 판단한다. 판단 이력(승인/기각 사유)은 GitHub에 남아 포트폴리오의 "판단 로그"가 된다.

### 비목표
- 사람 개입 없는 완전 자율 머지
- CI(GitHub Actions) 도입 — 별도 이슈로 분리
- 이슈 간 병렬 구현

## 2. 결정 사항 요약

| 결정 | 선택 | 근거 |
|---|---|---|
| 실행 환경 | 로컬 Mac (launchd → `claude -p`) | 기존 Claude 로그인·Docker 재사용, 추가 과금 없음 |
| 오케스트레이션 | 라벨 상태 머신 + 셸 디스패처 (1 tick = 1 단계) | 상태가 GitHub에만 존재 → 재시작 안전, 사람이 라벨로 개입 |
| 리뷰 판정 | B 자율 판정(근거를 thread reply로) + 머지 게이트 | 게이트 수를 3개로 유지, 판정 근거는 머지 시 일괄 검토 |
| A 트리거 | 백로그 기반 (대기 이슈 < 5 & 24h 경과 시, 1회 최대 3개) | 사람이 검토 못 하는 속도로 제안이 쌓이는 것 방지 |
| 기획 범위 | 기존 문서를 constitution으로 고정, 그 안에서 A가 발굴 | 큰 틀만 주면 Non-Goals 침범·요구사항 날조 |
| 동시 실행 | 구현·리뷰 중인 작업 전역 1개 | 정산 이슈가 선후 의존, 충돌 방지 |
| 라벨 전이 주체 | 디스패처(셸)만 `ai:*` 라벨을 변경 | 상태 머신을 결정적 코드에 두어 테스트 가능 |

## 3. Constitution (에이전트 판단 기준 문서)

새 문서를 만들지 않고 기존 문서를 그대로 쓴다.

| 문서 | 역할 |
|---|---|
| `docs/00-project-brief.md` | 목표·**Non-Goals** (범위 경계) |
| `docs/01-requirements.md`, `docs/02-domain-rules.md` | 도메인 불변식 |
| `docs/adr/*` | 이미 내린 결정 — 재논의 금지, 변경 제안은 `discussion` 이슈로만 |
| `docs/review-guide.md` | 구현·리뷰 체크리스트 |
| `.claude/workflow.md`, `review-process.md`, `codex-delegation.md`, `github-rules.md` | 작업 절차 (파이프라인 모드 예외는 각 스킬에 명시) |

Non-Goals를 건드리는 제안은 금지가 아니라 `ai:scope-change` 라벨을 추가로 달아 사람이 범위 변경 여부를 판단하게 한다.

## 4. 라벨 상태 머신

```
[A 제안]  ai:proposed (+ai:scope-change)
              │ 👤 G1: ai:ready 로 교체 / 기각 시 close + ai:rejected + 사유 코멘트
[기존 이슈] ──👤 ai:ready 부착
              ▼
          ai:ready ──🤖 plan──┬──▶ ai:needs-human   (타당성 반박·질문)
                               └──▶ ai:plan-review   👤 G2: ai:plan-approved
                                         ▲               또는 피드백 + ai:plan-revise
                                         └──🤖 replan────┘
          ai:plan-approved ──🤖 implement──▶ (issue) ai:in-pr
                                             (PR)    ai:reviewing
          (PR) ai:reviewing ──🤖 review 라운드 (최대 3)──▶ (PR) ai:merge-ready  👤 G3: 머지
                                                    └─ 3라운드 초과 ──▶ (PR) ai:needs-human
          모든 단계 실패/타임아웃 ──▶ ai:blocked (+사유 코멘트)
          ai:paused (어느 상태와도 병행) ──▶ 디스패처가 해당 이슈/PR 무시
```

### 라벨 목록

| 라벨 | 대상 | 의미 | 부착 주체 |
|---|---|---|---|
| `ai:proposed` | issue | A가 제안, 사람 검토 대기 | 디스패처 |
| `ai:scope-change` | issue | Non-Goals·기존 ADR과 충돌하는 제안 | A(스킬) |
| `ai:rejected` | issue | 사람이 기각 | 사람 |
| `ai:ready` | issue | B가 계획을 세워도 됨 | 사람 |
| `ai:needs-human` | issue/PR | 에이전트가 판단 불가·반박 | 디스패처 |
| `ai:plan-review` | issue | 계획 게시됨, 사람 검토 대기 | 디스패처 |
| `ai:plan-revise` | issue | 사람이 피드백 남김, 재계획 요청 | 사람 |
| `ai:plan-approved` | issue | 구현 착수 가능 | 사람 |
| `ai:implementing` | issue | 구현 진행 중 (실행 중 표식) | 디스패처 |
| `ai:in-pr` | issue | PR 생성됨 | 디스패처 |
| `ai:reviewing` | PR | 리뷰 루프 진행 중 | 디스패처 |
| `ai:merge-ready` | PR | 사람 머지 대기 | 디스패처 |
| `ai:blocked` | issue/PR | 실패·타임아웃, 사람 조치 필요 | 디스패처 |
| `ai:paused` | issue/PR | 파이프라인이 건드리지 않음 | 사람 |

## 5. 디스패처

`tools/agent-pipeline/dispatch.sh` — launchd가 15분마다 실행. **한 번 실행에 한 단계만** 수행하고 종료한다.

### 우선순위 (먼저 매칭되는 하나만 실행)

1. 전역 정지: `PIPELINE_ENABLED != true` 또는 `tools/agent-pipeline/STOP` 파일 존재 → 종료
2. 실행 중 잠금(`mkdir` 락, 6시간 초과 시 stale 처리) → 다른 실행이 있으면 종료
3. **고아 정리**: 락이 없는데 `ai:implementing` 인 이슈 → 이전 실행이 비정상 종료한 것 → `ai:blocked`
4. **review**: `ai:reviewing` PR 중 가장 오래된 것
5. **implement**: 진행 중(`ai:reviewing` PR) 작업이 없을 때, `ai:plan-approved` 이슈 중 선행 이슈가 모두 닫힌 가장 오래된 것
6. **replan**: `ai:plan-revise` 이슈
7. **plan**: `ai:ready` 이슈 중 선행 이슈가 모두 닫힌 가장 오래된 것
8. **propose**: 대기 백로그(`ai:proposed` + `ai:ready` + `ai:plan-review` + `ai:plan-revise` + `ai:plan-approved`) < `BACKLOG_MIN` 이고 마지막 propose 후 `PROPOSE_INTERVAL_HOURS` 경과
9. 할 일 없음 → 종료

`ai:paused`, `ai:blocked`, `ai:needs-human` 이 붙은 대상은 모든 단계에서 제외한다.

### 선행 이슈 표기
이슈 본문의 `선행: #197, #196` 줄. 나열된 이슈가 모두 CLOSED일 때만 plan/implement 대상이 된다.

### 단계 실행과 결과 계약
디스패처는 단계별 스킬을 헤드리스로 실행한다.

```
claude -p "<지시서 tools/agent-pipeline/prompts/<stage>.md 를 읽고 수행, 대상: #번호>" \
       --model <stage 모델> --settings tools/agent-pipeline/claude-settings.json \
       --permission-mode acceptEdits --add-dir tools/agent-pipeline --output-format json
```

스킬은 마지막 줄에 결과 한 줄을 출력한다. **`ai:*` 라벨은 스킬이 바꾸지 않고 디스패처가 결과에 따라 바꾼다.**

| 단계 | 결과 | 디스패처 전이 |
|---|---|---|
| propose | `PIPELINE_RESULT: proposed <n1> <n2> ...` | 각 이슈에 `ai:proposed` |
| propose | `PIPELINE_RESULT: none` | 없음 (타임스탬프만 갱신) |
| plan/replan | `PIPELINE_RESULT: planned` | `ai:ready`/`ai:plan-revise` → `ai:plan-review` |
| plan/replan | `PIPELINE_RESULT: invalid` | → `ai:needs-human` |
| implement | `PIPELINE_RESULT: pr <PR번호>` | issue `ai:implementing` → `ai:in-pr`, PR에 `ai:reviewing` |
| review | `PIPELINE_RESULT: approved` | PR `ai:reviewing` → `ai:merge-ready` |
| review | `PIPELINE_RESULT: changes-pushed` | 없음 (다음 tick에 다음 라운드). 라운드 수 ≥ `MAX_REVIEW_ROUNDS` 면 → `ai:needs-human` |
| 공통 | `PIPELINE_RESULT: blocked <사유>`, 결과 줄 없음, 타임아웃, 비정상 종료 | → `ai:blocked` + 사유 코멘트 |

리뷰 라운드 수는 PR 코멘트 중 `<!-- ai-review-round -->` 마커 개수로 센다(상태를 GitHub에만 둔다).

### 안전장치
- **권한**: `claude-settings.json` 의 allow/deny. git 은 하위 명령 허용 목록 + `git -*`(전역 옵션 우회) deny, GitHub API 는 `bin/gh-api` 허용 목록 래퍼만. 단 `node`/`npm`/`find`/`awk` 등 범용 실행 허용이 남아 있어 **이 목록은 실수 방지용이지 보안 경계가 아니다** — 실질 백스톱은 사람 머지 게이트와 GitHub 브랜치 보호(후속 과제: 파이프라인 전용 fine-grained 토큰). `gh pr merge`, `git push --force*`, `git push * main`, `gh issue close`, `gh label *`, `rm -rf` 는 deny.
- **타임아웃**: 단계별 (`propose` 30m, `plan` 30m, `implement` 120m, `review` 60m), `gtimeout` 사용.
- **격리**: plan/implement/review 모두 `origin/main` 기반 전용 워크트리(`.claude/worktrees/ai-<번호>`)에서 실행. 메인 체크아웃은 건드리지 않는다.
- **로그**: `tools/agent-pipeline/logs/` (gitignore) 에 실행별 JSON 출력 + `runs.log` 한 줄 요약.
- **알림**: 사람 게이트 도달·blocked 시 macOS 알림(`osascript`). GitHub 코멘트 알림은 기본으로 따라온다.
- **유형별 G2 생략**: `AUTO_APPROVE_PLAN_TYPES` 에 type 라벨(예: `docs`)을 넣으면 계획 게시 후 바로 `ai:plan-approved`. 기본값은 빈 값(모든 유형에 G2 적용).
- **규칙 문서 예외 명시**: `CLAUDE.md`·`review-process.md`·`workflow.md` 에 파이프라인 모드 예외(리뷰 [4] 자율 판정, 계획 수용 = 라벨)를 명시. 헤드리스 에이전트가 하드 게이트 규칙과 지시서 사이에서 충돌하지 않게 한다.
- **기본 비활성**: plist는 설치만 하고 `launchctl load` 는 사람이 직접 한다. `config.env` 의 `PIPELINE_ENABLED=false` 가 기본값.

## 6. 에이전트 지시서

모두 `tools/agent-pipeline/prompts/*.md` (`plan.md` 는 plan/replan 공용, `MODE` 파라미터로 구분). 스킬(`.claude/skills/`)이 아닌 절대경로 지시서로 둔 이유: 각 단계는 `origin/main` 기반 워크트리에서 실행되므로, 머지 전·후 어느 시점에도 디스패처와 같은 버전의 지시서를 읽게 하기 위함. 공통 규칙: 결과 줄 계약 준수, `ai:*` 라벨 변경 금지, 사람에게 질문하지 않음(헤드리스) — 막히면 `blocked`/`invalid` 로 끝내고 코멘트로 남긴다.

### 6.1 `propose.md` (A)
- **입력**: constitution 문서, 열린/닫힌 이슈 제목, `ai:rejected` 이슈와 기각 사유, 열린 마일스톤 잔여 이슈, 코드 구조.
- **관점**: 면접관이 공격할 지점 — 동시성·정합성·장애 전파·멱등성·관측성·테스트 공백·문서↔코드 불일치·미완 마일스톤(정산, 어드민 프론트).
- **규칙**: 기존 이슈와 중복 금지(제목·본문 검색 결과를 본문에 기록), 근거는 반드시 `파일:라인` 또는 문서 인용, 기각된 제안 재제안 금지, 1회 최대 `PROPOSE_MAX` 개, 이슈 템플릿 형식 준수 + "포트폴리오 관점 근거" 섹션 + 필요한 경우 `선행:` 줄.
- **출력**: `gh issue create` (type 라벨 + 마일스톤, Non-Goals/ADR 충돌 시 `ai:scope-change`).

### 6.2 `plan.md` (B — 타당성 판별 + 계획)
- 이슈의 주장을 코드에서 직접 재현·확인한다. 근거가 틀렸거나, constitution과 충돌하거나, 이미 해결됐으면 → 반박 코멘트 + `invalid`.
- 타당하면 `superpowers:writing-plans` 형식의 구현 계획을 이슈 코멘트로 게시(`<!-- ai-plan v1 -->` 마커). 계획에는 변경 파일, 테스트 전략, ADR 필요 여부, 위험 요소를 포함한다.
- replan 모드: 마지막 계획 이후의 사람 코멘트를 피드백으로 반영해 `v(n+1)` 게시.

### 6.3 `implement.md` (B — 구현)
- `.claude/workflow.md` 3~8단계를 따른다. 예외: 계획 승인은 이미 G2에서 끝났다.
- 승인된 최신 계획을 `docs/superpowers/plans/` 에 저장·커밋.
- 구현은 `codex-delegation.md`(서브에이전트 위임 규칙) 대로 Task 별 fresh 서브에이전트에 위임. Codex CLI 는 2026-09-17 부로 사용 중단(CLAUDE.md)이므로 권한에서 차단.
- 검증: 서버 변경 → `./gradlew test`, `admin-web` 변경 → `npm ci && npm run build`. 실패 시 최대 3회 수정 재시도 후 `blocked`.
- PR: `github-rules.md` 형식, `close #N`, 마일스톤 연결, 본문에 "🤖 에이전트 파이프라인 생성" 표기와 계획 코멘트 링크.

### 6.4 `review.md` (B — 리뷰 1라운드)
- `.claude/review-process.md` 의 [0]~[5]를 1라운드 수행. **예외: [4] 사용자 합의 대신 자율 판정**하되, 각 thread reply에 `[수용]/[거부]/[보류]` + 근거를 반드시 남긴다.
- 라운드 끝에 `<!-- ai-review-round -->` 마커가 든 요약 코멘트 게시.
- 남은 수용 finding이 없으면 `approved`, 수정 push 했으면 `changes-pushed`.

## 7. 파일 구성

```
tools/agent-pipeline/
├── README.md                 설치·운영·게이트 조작법
├── config.env                모델·타임아웃·임계값·PIPELINE_ENABLED
├── claude-settings.json      헤드리스 권한 allow/deny
├── dispatch.sh               디스패처 진입점
├── lib.sh                    gh 조회·라벨 전이·결과 파싱 함수
├── labels.sh                 ai:* 라벨 생성(멱등)
├── com.gongu.agent-pipeline.plist   launchd (15분 간격)
├── install.sh                plist 복사 (load 는 하지 않음)
├── prompts/{propose,plan,implement,review}.md   단계별 지시서
├── logs/                     (gitignore)
└── test/run-tests.sh         조회·변경 함수를 가짜로 교체하는 단위·통합 테스트
```

## 8. 테스트 전략

- **디스패처 단위 테스트**: `gh`·`claude` 를 PATH 모킹해 (a) 우선순위 선택 (b) 선행 이슈 판정 (c) 결과 줄 → 라벨 전이 (d) 타임아웃·결과 줄 누락 → blocked (e) 라운드 상한 (f) paused/STOP/락 처리를 검증한다.
- **dry-run**: `DRY_RUN=1 dispatch.sh` 는 실제 GitHub 상태를 읽어 "다음에 실행할 단계와 대상"만 출력한다.
- **실동작 스모크**: 실제 이슈 1개로 plan 단계만 수동 1회 실행해 계획 코멘트·라벨 전이를 확인한다.

## 9. 운영 시나리오 예시 (정산 마일스톤)

1. 사람이 #194(정산 ADR)에 `ai:ready` 부착 → B가 계획 게시 → 사람이 `ai:plan-approved` → PR → 리뷰 루프 → 사람 머지.
2. #197·#199는 본문에 `선행: #194` 를 추가해 두면 #194가 닫힐 때까지 자동 대기.
3. 백로그가 줄면 A가 "정산 대사 결과 조회 API 부재" 같은 후속 허점을 `ai:proposed` 로 제안.

## 10. 향후 확장 (이번 범위 밖)
- CI 도입 후 implement 결과에 CI 통과를 조건으로 추가
- 디스패처를 Agent SDK 프로그램으로 교체 (스킬·결과 계약은 유지)

## 11. 결정 기록 — 검토한 대안

| # | 결정 지점 | 택한 안 | 검토한 대안 | 결정 주체 |
|---|---|---|---|---|
| D1 | 실행 환경 | 로컬 Mac (launchd) | GitHub Actions(API 과금·CI용 DB 구성 필요) / 하이브리드(A는 클라우드 스케줄, B는 로컬) | 사용자 |
| D2 | 리뷰 판정 | 자율 판정 + 머지 게이트 | 판정 게이트 유지(PR당 사람 개입 +1) / 중요도별 분기(Critical 거부만 사람 승인) | 사용자 |
| D3 | A 트리거 | 백로그 기반 | 고정 주기 / 수동 실행만 | 사용자 |
| D4 | 오케스트레이션 | 라벨 상태 머신 + 셸 디스패처 | 상주 세션 `/loop`(컨텍스트 누적) / Agent SDK 프로그램(코드량 큼) | 사용자 |
| D5 | 라벨 전이 주체 | 디스패처(셸)만 | 각 에이전트가 직접 라벨 변경(상태 머신이 프롬프트에 흩어져 테스트 불가) | 에이전트(권장안) |
| D6 | 지시서 위치 | `tools/agent-pipeline/prompts/` 절대경로 | `.claude/skills/`(origin/main 워크트리에서는 머지 전 부재, 버전 불일치) | 에이전트(권장안) |
| D7 | 동시 실행 | 구현·리뷰 전역 1건 | 이슈별 병렬(정산 이슈 선후 의존·충돌) | 에이전트(권장안) |
| D8 | 리뷰 "방법 탐색 후 논의" 판정 | 권장안 진행 + `[수용-설계판단]` 기록 | 해당 PR 만 `ai:needs-human` 으로 정지 | 에이전트(권장안) |
| D9 | 규칙 문서 충돌 | CLAUDE.md·review-process.md·workflow.md 에 파이프라인 예외 명시 | 지시서에서만 예외 선언(하드 게이트 문구와 충돌해 에이전트가 멈출 위험) | 에이전트(권장안) |
| D10 | 환경 오류(인증 만료 등) | 라벨 무변경 + `STOP` 파일로 전체 정지 | 이슈에 `ai:blocked`(초기 구현 — 인증 끊긴 동안 15분마다 이슈가 하나씩 blocked 되는 결함을 실동작에서 발견해 교체) | 에이전트(실측 후 수정) |
| D11 | 모델 | 전 단계 opus | 구현·리뷰 sonnet(비용↓, 판정 품질↓) — `config.env` 로 조정 | 에이전트(권장안) |
| D12 | 활성화 | plist 설치만, 기본 `PIPELINE_ENABLED=false` | 즉시 가동(무인 상태에서 실제 이슈 구현 시작) | 에이전트(권장안) |
| D13 | GitHub API 권한 (리뷰 R1) | `bin/gh-api` 허용 목록 래퍼 | deny 블랙리스트 확장 / 머지 게이트에만 의존 | 에이전트(권장안) |
| D14 | 리뷰 워크트리 (리뷰 R1) | 항상 전용 detached `ai-pr-N` | 파이프라인 소유 워크트리만 재사용 / 하이브리드(판정 서브에이전트 권장) | 에이전트(단순성 우선) |
| D15 | fetch 실패 (리뷰 R1) | 라벨 무변경 skip, 연속 3회 시 STOP | 즉시 STOP / 인증 패턴만 즉시 STOP | 에이전트(권장안) |
| D16 | 스레드 resolve (리뷰 R2) | 파이프라인에서 생략, 사람이 머지 시 | 래퍼에서 resolve mutation 고정 템플릿만 허용 | 에이전트(권장안) |
| D17 | 외부 작성 이슈 (리뷰 R4) | 신뢰 작성자(OWNER/MEMBER/COLLABORATOR) 이슈만 선택 | 승인 시 본문 스냅샷 / 프롬프트 지시만 | 에이전트(권장안) |
| D18 | 부분 라벨 전이 (리뷰 R4) | 새 라벨 제거 + 이전 라벨 재추가 롤백, 실패 시 STOP | 새 라벨만 제거(응답 유실 시 상태 라벨 0개 위험) | 에이전트(판정 보강안) |
| D19 | 구현·리뷰 위임 대상 (리뷰 R7) | Claude fresh 서브에이전트 + 계획 외 삭제 검사, Codex 권한 차단 | Codex CLI 위임(2026-09-17 CLAUDE.md 에서 사용 중단 — 기존 파일 덮어쓰기 사고) | 프로젝트 규칙 |

### 잔여 위험 (후속 과제)
- 헤드리스 세션의 `node`/`npm`/`find`/`awk`/`Read` 허용은 임의 실행·파일 읽기 경로가 될 수 있다. 권한 목록은 실수 방지용이며 보안 경계가 아니다. 근본 대책은 **파이프라인 전용 fine-grained 토큰**(이 저장소 issues/PR/contents 쓰기만)과 **main 브랜치 보호**.
- CI 부재 — 자동 생성 PR 의 검증이 로컬 테스트와 리뷰에만 의존한다.
