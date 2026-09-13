# k6 진행률 라인 중복 캡처 수정 (#191) Implementation Plan

> **For agentic workers:** 이 프로젝트는 구현을 Codex CLI에 위임한다(CLAUDE.md 역할 분리). 아래 태스크는 `codex exec`로 위임하고, Claude는 설계·검증·GitHub 관리를 담당한다.

**Goal:** `run_and_report.py`가 k6 진행률 표시줄을 반복 캡처해 Notion 413을 유발하는 문제를, k6 `--quiet` 플래그로 진행률 출력을 원천 억제해 해결한다.

**Architecture:** 근본 원인은 비-TTY 파이프 환경에서 k6가 진행률 블록(`running (...)`, `order_tps_* [ x% ]`)을 매초 새 줄로 출력하는 것이다. `k6 run` 서브커맨드에 `-q/--quiet`("disable progress updates")를 추가하면 진행률 출력만 억제되고, 별도 핸들러가 출력하는 종료 요약(CUSTOM/HTTP/EXECUTION/NETWORK 블록)은 그대로 남는다. 결과적으로 캡처 stdout이 정상 케이스(15~17KB) 수준으로 유지되어 Notion 코드 블록 업로드가 413 없이 성공한다.

**Tech Stack:** Python 3, k6 (docker compose `k6` 프로파일), Notion API

## Global Constraints

- Simplicity First / Surgical Changes: 요청 범위(진행률 중복 캡처) 밖의 코드는 건드리지 않는다. 방어적 truncation·요약 파싱 등 부가 로직은 추가하지 않는다 — 근본 원인을 소스에서 제거하는 최소 변경만 한다.
- 커밋 메시지 형식: `type: 작업 내용 (#191)`, `Co-Authored-By` 절 금지.
- 기존 정상 케이스(짧고 성공률 높은 시나리오) 출력 형식에 회귀가 없어야 한다(요약 블록 보존).

---

### Task 1: k6 실행 커맨드에 `--quiet` 추가

**Files:**
- Modify: `load-test/report/run_and_report.py` — `build_k6_command()` (현재 150-161행)

**변경 내용:**

`build_k6_command()`가 반환하는 리스트의 마지막 k6 `run` 서브커맨드에 `--quiet`를 추가한다. 현재:

```python
def build_k6_command(scenario: str) -> list[str]:
    return [
        "docker",
        "compose",
        "--profile",
        "k6",
        "run",
        "--rm",
        "k6",
        "run",
        f"/scripts/scenarios/{scenario}.js",
    ]
```

변경 후:

```python
def build_k6_command(scenario: str) -> list[str]:
    return [
        "docker",
        "compose",
        "--profile",
        "k6",
        "run",
        "--rm",
        "k6",
        "run",
        "--quiet",
        f"/scripts/scenarios/{scenario}.js",
    ]
```

주의: `--quiet`는 마지막 `run`(= k6 run 서브커맨드) 뒤에 위치해야 한다. 앞쪽 `run`은 `docker compose run`이므로 혼동하지 않는다.

- [ ] **Step 1: `--quiet` 인자 추가** (위 diff대로 `build_k6_command` 수정)

- [ ] **Step 2: 구성된 커맨드 검증**

Run:
```bash
cd load-test/report && python3 -c "import run_and_report as r; print(r.build_k6_command('08-order-tps-optimistic'))"
```
Expected: 출력 리스트에 `'run', '--quiet', '/scripts/scenarios/08-order-tps-optimistic.js'` 순서가 포함됨.

- [ ] **Step 3: 커밋**

```bash
git add load-test/report/run_and_report.py
git commit -m "fix: k6 실행에 --quiet 추가해 진행률 라인 중복 캡처 방지 (#191)"
```

---

## 검증 (완료 기준)

Task 1 커밋 후, 통합 검증은 사용자 환경(도커 스택·Notion 토큰 필요)에서 수행한다:

1. **진행률 반복 억제 확인**: 장시간·고실패율 시나리오 재실행 후 `load-test/reports/<ts>/k6-output.txt`에 `running (` / `order_tps_* [ x% ]` 진행률 블록이 반복 출력되지 않고, 파일 크기가 정상(수십 KB) 수준인지 확인.
   ```bash
   python3 load-test/report/run_and_report.py 08-order-tps-optimistic --condition "..."
   ```
2. **Notion 413 미발생**: 위 실행에서 Notion 블록 append가 413 없이 성공(`[notion] Appended load-test report: ...` 출력).
3. **회귀 없음**: 짧고 성공률 높은 시나리오에서도 종료 요약(CUSTOM/HTTP/EXECUTION/NETWORK) 블록이 Notion 코드 블록에 그대로 포함되는지 확인.

## Self-Review 결과

- 스펙 커버리지: 완료 기준 3개(진행률 반복 억제 / 413 미발생 / 회귀 없음) 모두 `--quiet` 적용 + 위 검증 절차로 커버. `--quiet`는 `k6 run --help`에서 "disable progress updates"로 확인됨(요약은 별도 핸들러라 보존).
- Placeholder 없음.
- 타입 일관성: 단일 함수 시그니처 변경, 반환 타입(`list[str]`) 유지.
