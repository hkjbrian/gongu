# 에이전트 파이프라인

이슈 제안 → 계획 검토 → 구현 → 리뷰 → 머지를 자동화하는 GitHub 라벨 상태 머신. [설계 문서](../../docs/superpowers/specs/2026-10-03-agent-pipeline-design.md) 참고.

## 흐름 요약

```
[A 제안]  ai:proposed
              │ 👤 G1: ai:ready 로 교체 / 기각 시 close + ai:rejected + 사유
[기존 이슈] ──👤 ai:ready 부착
              ▼
          ai:ready ──🤖 plan──┬──▶ ai:needs-human
                               └──▶ ai:plan-review   👤 G2: ai:plan-approved
                                         ▲               또는 ai:plan-revise
                                         └──🤖 replan────┘
          ai:plan-approved ──🤖 implement──▶ ai:in-pr
                                             ai:reviewing
          ai:reviewing ──🤖 review(최대 3라운드)──▶ ai:merge-ready  👤 G3: 머지
                                         └─ 3라운드 초과 ──▶ ai:needs-human
          실패/타임아웃 ──▶ ai:blocked (+사유 코멘트)
          ai:paused ──▶ 디스패처가 무시
```

## 사람이 하는 일

| 게이트 | 동작 | 라벨 변경 |
|--------|------|----------|
| **G1** (제안 승인) | ai:proposed 이슈 검토 | ai:proposed → ai:ready 또는 close + ai:rejected + 사유 코멘트 |
| **G1** (기존 이슈) | 대기 중인 이슈에 | ai:ready 부착 |
| **G2** (계획 승인) | ai:plan-review 코멘트 검토 | ai:plan-review → ai:plan-approved 또는 코멘트 + ai:plan-revise |
| **G3** (머지) | ai:merge-ready PR 검토 | `gh pr merge` 직접 실행 |
| **blocked** | 사유 코멘트·로그 확인 후 원인 해결 | ai:blocked 제거 + 재개할 상태 라벨 부착 (예: ai:plan-approved) |
| **needs-human** | 에이전트의 반박·질문 코멘트에 답변 | ai:needs-human 제거 + ai:ready(재판별) 또는 close |
| **일시정지** | 특정 이슈/PR 을 파이프라인에서 제외 | ai:paused 부착 / 제거로 재개 |
| **선행 이슈** | (자동) 이슈 본문에 `선행: #번호` 줄로 표기 |

## 설치

1. **브랜치 머지 후 메인 체크아웃**: plist가 메인 체크아웃의 dispatch.sh 경로를 가리킴
   ```bash
   cd /Users/hankyungjun/projects/gongu/server && git pull
   ```

2. **라벨 생성**:
   ```bash
   ./tools/agent-pipeline/labels.sh
   ```

3. **plist 설치**:
   ```bash
   ./tools/agent-pipeline/install.sh
   ```

4. **파이프라인 활성화**:
   ```bash
   echo PIPELINE_ENABLED=true > tools/agent-pipeline/config.local.env
   ```

5. **launchd 시작**:
   ```bash
   launchctl load ~/Library/LaunchAgents/com.gongu.agent-pipeline.plist
   ```

## 운영 명령

**다음 작업 확인**: `bash tools/agent-pipeline/dispatch.sh --dry-run`

**수동 실행**: `bash tools/agent-pipeline/dispatch.sh --stage <stage> <번호>`
- propose는 번호 대신 `-` 사용
- stage: propose, plan, replan, implement, review

**긴급 정지**:
- `touch tools/agent-pipeline/STOP` (임시 정지, 파일 제거로 재개)
- `launchctl unload ~/Library/LaunchAgents/com.gongu.agent-pipeline.plist` (완전 정지)

**로그 확인**:
- `logs/runs.log` — 각 실행 한 줄 요약
- `logs/runs/*.json` — 단계별 Claude 출력
- `logs/launchd.out.log`, `logs/launchd.err.log` — launchd 로그

**테스트**: `bash tools/agent-pipeline/test/run-tests.sh`

## 설정 키 (config.env)

| 키 | 기본값 | 의미 |
|----|--------|------|
| PIPELINE_ENABLED | false | 전역 스위치; true만 정기 실행 시작 |
| REPO | hkjbrian/gongu | 대상 GitHub 저장소 |
| MODEL_PROPOSE/PLAN/IMPLEMENT/REVIEW | opus | 단계별 모델 |
| TIMEOUT_PROPOSE/PLAN/IMPLEMENT/REVIEW | 30m/30m/120m/60m | 단계별 타임아웃 |
| BACKLOG_MIN | 5 | 제안 발동 조건: 대기 이슈 < 이 값 |
| PROPOSE_INTERVAL_HOURS | 24 | 마지막 제안 후 경과 시간 (시간) |
| PROPOSE_MAX | 3 | 1회 제안 최대 개수 |
| MAX_REVIEW_ROUNDS | 3 | 리뷰 라운드 상한 |
| AUTO_APPROVE_PLAN_TYPES | "" | 계획 자동 승인할 type (공백 구분, 예: "docs chore") |
| NOTIFY | true | 게이트 도달/blocked 시 macOS 알림 |
| LOCK_STALE_SECONDS | 21600 | 락이 오래되면 비정상 종료 간주 (초) |

## 안전장치

**권한**: claude-settings.json의 deny 목록
- 머지: `gh pr merge *` 금지
- force push: `git push --force*`, `git push * +*` 금지
- main push: `git push origin main*`, `git push * main` 금지
- 라벨 조작: `gh label *` 금지
- 위험한 명령: `rm -rf *`, `launchctl *`, `sudo *` 금지

**GitHub API**: 헤드리스 세션은 `gh api` 대신 `bin/gh-api` 래퍼만 사용 — 이 저장소의 조회와 코멘트/reply 작성만 허용

**신뢰 경계**: 공개 저장소이므로 OWNER/MEMBER/COLLABORATOR 가 작성한 이슈만 파이프라인에 들어온다(외부 제안은 사람이 재작성). 코멘트도 같은 기준으로만 지시로 취급

**자동 정지(STOP 파일 생성)**: Claude 인증 만료·API 장애 / `git fetch` 연속 3회 실패 / 라벨 전이 롤백 실패. 원인 해결 후 `tools/agent-pipeline/STOP` 삭제

**타임아웃**: 단계별 설정값 초과 시 자동 중단

**동시 실행**: `mkdir` 락으로 전역 1건만 실행 (중복 방지)

**워크트리 격리**: plan/implement/review는 각각 `.claude/worktrees/ai-*` 전용 워크트리에서 실행

## 알려진 제약

- **Mac이 깨어 있어야 함**: launchd는 절전 모드에서 미실행
- **파이프라인 수정은 머지 후 반영**: plist가 메인 체크아웃의 dispatch.sh를 가리키므로
- **메인 체크아웃 자체는 건드리지 않음**: 에이전트는 항상 `origin/main` 기반 워크트리에서 작업한다. 메인 체크아웃의 미커밋 변경은 영향받지 않지만, `tools/agent-pipeline/` 은 최신이어야 한다
- **admin-web**: 테스트 부재로 `npm run build` 통과만 검증
- **CI 부재**: GitHub Actions 미도입 (별도 이슈)
- **권한 목록은 보안 경계가 아님**: `node`/`npm`/`find` 등 범용 실행이 허용되어 있다. 파이프라인 전용 fine-grained 토큰과 main 브랜치 보호를 권장
- **Claude CLI 로그인 필요**: 헤드리스 `claude -p` 는 OAuth 세션이 살아 있어야 한다(만료 시 STOP)
