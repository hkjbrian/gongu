# 부하테스트 결과 기록 자동화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** k6 부하테스트 실행 → Grafana 스크린샷 캡처 → Notion 페이지 기록까지 한 명령(`load-test/report/run_and_report.py`)으로 자동화한다.

**Spec:** `docs/superpowers/specs/2026-07-03-load-test-report-automation-design.md`

**Tech Stack:** Python 3 (requests, python-dotenv), Grafana `grafana-image-renderer` 별도 컨테이너, Notion REST API, 기존 k6/docker-compose 인프라

**Issue:** #169 (브랜치 `feat/#169-load-test-report-automation`)

---

## 사전 준비 (사용자가 직접 처리 — 태스크 범위 밖)

- KJ 워크스페이스에 Notion Internal Integration 생성, secret token 발급
- 대상 페이지("부하테스트 개선 진행", page id `388e5691-46a6-8021-95f8-fe481ee7e586`)의 `...` 메뉴 → Connections에서 위 Integration 연결
- 로컬 `.env`에 `NOTION_TOKEN`, `NOTION_PAGE_ID` 값 채워넣기 (Task 5에서 키 추가, 값은 사용자가 채움)

---

## File Map

**신규 파일:**
- `monitoring/grafana/dashboards/jvm-micrometer.json`
- `monitoring/grafana/dashboards/mysql-exporter-quickstart.json`
- `monitoring/grafana/dashboards/spring-boot-3x-statistics.json`
- `load-test/report/run_and_report.py`
- `load-test/report/requirements.txt`

**수정 파일:**
- `docker-compose.yml` — `grafana-renderer` 서비스(별도 렌더러 컨테이너) 추가, grafana 서비스에 원격 렌더링 설정 추가
- `.env` — `NOTION_TOKEN`, `NOTION_PAGE_ID` 키 추가 (값은 플레이스홀더, git-ignored 파일이라 커밋 안 됨)
- `.gitignore` — `load-test/reports/` 추가

**건드리지 않을 파일:**
- `load-test/scenarios/07-order-tps.js`, `src/main/resources/application-perf.yml` — 현재 이슈 #168 작업 중인 미커밋 변경사항. 이번 이슈(#169)와 무관하므로 절대 add/commit하지 않는다.
- `monitoring/grafana/dashboards/gongu-dashboard.json` — 기존 대시보드, 패널 ID(14,15,16,17,19)는 이미 존재하므로 수정 불필요

---

## Task 1: Grafana 대시보드 3종 export 및 provisioning 커밋

**참고 문서/파일 (읽어야 할 것):**
- Spec: `docs/superpowers/specs/2026-07-03-load-test-report-automation-design.md` — "3.2 레포에 없는 대시보드 커밋"
- `monitoring/grafana/provisioning/dashboards/dashboard.yml` — provisioning 방식 확인 (디렉터리 기반 자동 탐지, `path: /var/lib/grafana/dashboards`)
- `monitoring/grafana/dashboards/gongu-dashboard.json` — 기존 커밋된 대시보드 JSON 포맷 참고 (동일한 export 포맷을 따를 것)

**수정 대상 파일:**
- Create: `monitoring/grafana/dashboards/jvm-micrometer.json`
- Create: `monitoring/grafana/dashboards/mysql-exporter-quickstart.json`
- Create: `monitoring/grafana/dashboards/spring-boot-3x-statistics.json`

**금지 사항:**
- `monitoring/grafana/dashboards/gongu-dashboard.json` 수정 금지
- `MySQL Simple Dashboard`(uid `4Z1opp5mk`)는 커밋 대상 아님 — export하지 않는다

**구현 방향 (WHAT, not HOW):**
- 로컬에서 실행 중인 Grafana(`http://localhost:3001`, basic auth `admin:admin`)의 API로 아래 3개 대시보드를 JSON export한다:
  - `GET /api/dashboards/uid/efoj0uvwhzq4gf` (JVM (Micrometer))
  - `GET /api/dashboards/uid/549c2bf8936f7767ea6ac47c47b00f2a` (MySQL Exporter Quickstart and Dashboard)
  - `GET /api/dashboards/uid/spring_boot_21` (Spring Boot 3.x Statistics)
- 응답의 `dashboard` 필드만 추출해 각각 파일로 저장한다 (`meta` 필드 등 API 래퍼는 제외 — `gongu-dashboard.json`이 순수 dashboard 객체만 담고 있는 것과 동일한 포맷으로 맞출 것)
- `id` 필드는 `null`로 초기화한다 (provisioning 재import 시 충돌 방지 — Grafana export 관례)

**검증:**
```bash
docker compose up -d grafana
sleep 5
curl -s -u admin:admin "http://localhost:3001/api/search?query=" | python3 -c "import json,sys; [print(d['title']) for d in json.load(sys.stdin)]"
```
Expected: `Gongu Service Overview`, `JVM (Micrometer)`, `MySQL Exporter Quickstart and Dashboard`, `Spring Boot 3.x Statistics` 가 모두 목록에 나타남 (provisioning volume이 `monitoring/grafana/dashboards`를 마운트하므로 컨테이너 재기동 시 자동 반영됨)

**커밋:**
```bash
git add monitoring/grafana/dashboards/jvm-micrometer.json monitoring/grafana/dashboards/mysql-exporter-quickstart.json monitoring/grafana/dashboards/spring-boot-3x-statistics.json
git commit -m "chore: 부하테스트 리포트용 Grafana 대시보드 3종 provisioning 추가 (#169)"
```

---

## Task 2: Grafana Image Renderer 별도 컨테이너 추가

> **변경 이력**: 최초 계획은 플러그인 내장 렌더링 모드였으나, 구현 중 개발 머신(arm64)에서 내장 렌더러가 동작하지 않아(플러그인 자체가 `linux/arm64` 비호환으로 설치 실패, amd64 강제 시 렌더링 서브프로세스가 `exit status 127`로 종료) 별도 렌더러 컨테이너 방식으로 변경됨. 최신 spec "3.1 Image Renderer — 별도 렌더러 컨테이너" 참고.

**참고 문서/파일 (읽어야 할 것):**
- Spec: `docs/superpowers/specs/2026-07-03-load-test-report-automation-design.md` — "3.1 Image Renderer — 별도 렌더러 컨테이너"
- `docker-compose.yml` — 기존 `grafana`, `prometheus` 서비스 블록과 `gongu-net` 네트워크 정의

**수정 대상 파일:**
- Modify: `docker-compose.yml`

**금지 사항:**
- `prometheus`, `mysql`, `mysqld-exporter` 등 다른 서비스 블록은 건드리지 않는다
- `GF_INSTALL_PLUGINS` 방식(내장 플러그인)으로 되돌리지 않는다 — arm64에서 확인된 실패 원인이므로 재시도 금지

**구현 방향 (WHAT, not HOW):**
- `docker-compose.yml`에 `grafana-renderer` 서비스 신규 추가: `image: grafana/grafana-image-renderer:latest`, `container_name: gongu-grafana-renderer`, `restart: unless-stopped`, 기존 `gongu-net` 네트워크에 연결
- `grafana` 서비스 `environment`에 `GF_RENDERING_SERVER_URL=http://grafana-renderer:8081/render`, `GF_RENDERING_CALLBACK_URL=http://grafana:3000/` 추가
- `grafana` 서비스 `depends_on`에 `grafana-renderer` 추가 (기존 `prometheus` 의존성은 유지)

**검증:**
```bash
docker compose up -d grafana-renderer grafana
sleep 15
docker compose logs grafana | grep -i "renderer\|rendering"
curl -s -u admin:admin "http://localhost:3001/render/d-solo/gongu-service-overview/x?panelId=16&from=now-1h&to=now&width=800&height=400" -o /tmp/test-render.png
file /tmp/test-render.png
```
Expected: 로그에 원격 렌더러 연결 관련 메시지(에러 없음), `/tmp/test-render.png`가 `PNG image data`로 판별됨 (빈 파일이나 에러 JSON이 아님)

Do NOT run `git commit` yourself — leave the change staged/unstaged in the working tree. The controller (a separate process) will review and commit it.

---

## Task 3: k6 실행 + 결과 캡처 + 로컬 백업 골격

**참고 문서/파일 (읽어야 할 것):**
- Spec: "2. 전체 데이터 흐름" 1~4단계, "5. 스크립트 구성", "6. 변경/신규 파일 목록"
- `load-test/scenarios/` 디렉터리 — 시나리오 파일 목록과 네이밍 (예: `07-order-tps.js`) 확인용, 파일 자체는 수정하지 않음
- `docker-compose.yml`의 `k6` 서비스 블록 (`profiles: [k6]`, `volumes: ./load-test:/scripts`) — 실행 커맨드 형식 확인

**수정 대상 파일:**
- Create: `load-test/report/run_and_report.py`
- Create: `load-test/report/requirements.txt`
- Modify: `.gitignore` — `load-test/reports/` 추가

**금지 사항:**
- `load-test/scenarios/*.js`, `load-test/lib/*.js` 수정 금지 (k6 스크립트는 그대로 사용)
- 이 태스크에서는 Grafana/Notion 연동을 구현하지 않는다 (Task 4, 5에서 추가) — 해당 부분은 함수 스텁 또는 `# TODO(Task 4)`, `# TODO(Task 5)` 주석으로 남겨 다음 태스크가 이어받을 지점을 명확히 표시

**구현 방향 (WHAT, not HOW):**
- `requirements.txt`: `requests`, `python-dotenv`
- `run_and_report.py`:
  - CLI: `python3 run_and_report.py <scenario-basename> --condition "<설명>"` (예: `07-order-tps`)
  - `.env`를 프로젝트 루트에서 로드 (`python-dotenv`)
  - 실행 시작 시각을 UTC epoch(ms)로 기록
  - `subprocess`로 `docker compose --profile k6 run --rm k6 run /scripts/scenarios/<scenario>.js` 실행. stdout을 실시간으로 터미널에 흘리면서 동시에 전체 텍스트를 문자열로 캡처 (예: `subprocess.Popen` + line-by-line read + 버퍼 누적)
  - 프로세스 종료 후 exit code로 pass/fail 판정 (`0` → pass, `non-zero` → fail), 종료 시각 기록
  - 30초 `time.sleep`
  - 이 시점까지 확보한 데이터(condition, pass/fail, k6 stdout 텍스트, start/end epoch)를 다음 태스크가 이어받을 수 있는 형태(dict 또는 dataclass)로 구성
  - 예외 발생 시(서브프로세스 실행 실패 등) `load-test/reports/<시작시각-ISO>/k6-output.txt`에 그때까지의 stdout을 저장하고 non-zero exit으로 종료

**검증:**
```bash
docker compose up -d server mock-pg
cd load-test/report && pip install -r requirements.txt
python3 run_and_report.py 07-order-tps --condition "테스트 실행"
```
Expected: 터미널에 k6 실행 로그가 실시간으로 출력되고, 종료 후 pass/fail이 콘솔에 출력됨 (Grafana/Notion 부분은 아직 TODO 상태이므로 여기서 멈추거나 스텁 로그만 출력해도 됨)

**커밋:**
```bash
git add load-test/report/run_and_report.py load-test/report/requirements.txt .gitignore
git commit -m "feat: k6 실행 및 결과 캡처 리포트 스크립트 골격 추가 (#169)"
```

---

## Task 4: Grafana 스크린샷 캡처 기능 추가

**참고 문서/파일 (읽어야 할 것):**
- Spec: "3.3 기본 캡처 패널 세트" 표 (대시보드 uid, 패널 ID 10개 전체 목록)
- `load-test/report/run_and_report.py` (Task 3 산출물) — 캡처 지점 TODO 주석 확인
- Grafana Render API 문서: `https://grafana.com/docs/grafana/latest/administration/image-rendering/` (엔드포인트 형식: `GET /render/d-solo/{uid}/{slug}?panelId={id}&from={ms}&to={ms}&width=&height=`)

**수정 대상 파일:**
- Modify: `load-test/report/run_and_report.py`

**금지 사항:**
- 패널 목록을 하드코딩된 상수 리스트 이외의 방식(예: Grafana API로 동적 탐색)으로 가져오지 않는다 — 스펙에서 고정 상수로 결정됨
- Task 3에서 만든 k6 실행/캡처 로직은 변경하지 않는다

**구현 방향 (WHAT, not HOW):**
- 스펙 "3.3" 표의 10개 (dashboard_uid, panel_id, 패널명)을 모듈 상단 상수 리스트로 정의
- Task 3에서 기록한 시작/종료 epoch(ms)에 종료 시각 기준 여유값을 더해 `from`/`to` 쿼리 파라미터로 사용 (Task 3의 30초 대기가 이미 끝난 시점이므로 `to`는 종료 시각 + 30초 정도로 데이터 누락 없이 잡는다)
- 각 패널에 대해 `requests.get`으로 `{GRAFANA_URL}/render/d-solo/{uid}/{slug}` 호출, basic auth는 `.env`의 `GRAFANA_USER`/`GRAFANA_PASSWORD` 재사용 (docker-compose와 동일 변수명)
- 응답 바이트를 로컬에 임시 저장 (`load-test/reports/<timestamp>/panel-<n>.png`) — Task 5에서 업로드에 사용
- 렌더링 실패(4xx/5xx, 빈 응답) 시 Task 3의 예외 처리 경로(로컬 백업 후 non-zero exit)로 합류

**검증:**
```bash
python3 run_and_report.py 07-order-tps --condition "테스트 실행"
ls load-test/reports/*/*.png | wc -l
file load-test/reports/*/panel-1.png
```
Expected: PNG 10장이 생성되고 각각 유효한 PNG 파일로 판별됨

**커밋:**
```bash
git add load-test/report/run_and_report.py
git commit -m "feat: Grafana 패널 10종 스크린샷 캡처 기능 추가 (#169)"
```

---

## Task 5: Notion 업로드 + 페이지 하단 토글 추가

**참고 문서/파일 (읽어야 할 것):**
- Spec: "2. 전체 데이터 흐름" 6~8단계, "4. 인증 / 시크릿"
- `load-test/report/run_and_report.py` (Task 3, 4 산출물)
- Notion File Upload API 공식 문서: `https://developers.notion.com/docs/uploading-small-files-directly` — 요청/응답 스키마와 필요한 `Notion-Version` 헤더 값을 문서에서 직접 확인할 것 (버전에 따라 필드명이 다를 수 있으므로 추측하지 말고 공식 문서 기준으로 구현)
- Notion Append Block Children API 문서: `https://developers.notion.com/reference/patch-block-children` — `PATCH /v1/blocks/{page_id}/children`는 기본적으로 children 배열을 페이지 맨 끝에 추가하므로, 기존 블록을 먼저 조회할 필요는 없다

**수정 대상 파일:**
- Modify: `load-test/report/run_and_report.py`
- Modify: `.env` — `NOTION_TOKEN=`, `NOTION_PAGE_ID=388e5691-46a6-8021-95f8-fe481ee7e586` 키 추가 (실제 토큰 값은 플레이스홀더로 두고 사용자가 채우도록 주석 남길 것)

**금지 사항:**
- Task 4까지 완성된 Grafana 캡처 로직은 변경하지 않는다
- `--condition`에 페이지 내 특정 섹션(A안/B안/C안)을 지정하는 옵션을 추가하지 않는다 (스펙에서 "항상 페이지 맨 하단"으로 확정됨)
- 토글 제목에 "N차" 번호를 자동으로 붙이지 않는다 (사용자가 Notion에서 직접 편집)

**구현 방향 (WHAT, not HOW):**
- Task 4에서 저장한 PNG 10장을 Notion File Upload API로 순서대로 업로드하고 각각의 `file_upload_id`를 획득
- k6 stdout 텍스트를 Notion `code` 블록(`language: "plain text"`)으로 변환. Notion 코드 블록의 `rich_text.text.content`는 2000자 제한이 있으므로, k6 출력이 이를 초과할 경우 여러 개의 `code` 블록으로 분할한다
- 토글(`toggle`) 블록 하나를 구성: `rich_text`는 `--condition` 값 + `" (o)"` 또는 `" (x)"` (Task 3에서 판정한 pass/fail), `children`은 위에서 만든 code 블록(들) + image 블록 10개(순서대로)
- `PATCH /v1/blocks/{NOTION_PAGE_ID}/children`으로 위 토글 블록을 append
- 업로드/append 중 실패 시 Task 3의 로컬 백업 경로에 이미 저장된 PNG/텍스트를 그대로 남겨두고 non-zero exit (재시도 시 사용자가 백업본으로 수동 게시 가능하도록)
- 성공 시 응답에서 생성된 블록 URL(또는 페이지 URL)을 콘솔에 출력

**검증:**
```bash
python3 run_and_report.py 07-order-tps --condition "자동화 파이프라인 검증"
```
Expected: 콘솔에 Notion 블록/페이지 URL이 출력되고, 실제로 `https://www.notion.so/388e569146a6802195f8fe481ee7e586` 페이지 맨 하단에 새 토글이 생성되어 있음 — 토글을 열면 k6 코드블록과 이미지 10장이 순서대로 보임. 의도적으로 threshold를 실패시키는 시나리오(예: 매우 높은 rate)로 1회 더 실행해 제목에 `(x)`가 붙는지 확인

**커밋:**
```bash
git add load-test/report/run_and_report.py .env
git commit -m "feat: Notion 업로드 및 페이지 하단 토글 기록 기능 추가 (#169)"
```
(`*.env`는 `.gitignore`에 걸려 있으므로 실제로는 `.env`가 add되지 않는다 — 만약 add된다면 `.gitignore` 패턴을 먼저 점검할 것)

---

## Self-Review 체크리스트 (계획 작성자 기록)

- [x] 스펙의 각 섹션(2~8)이 최소 하나의 태스크에 대응됨
- [x] 모든 태스크에 실행 가능한 검증 명령 존재
- [x] 명시한 파일 경로(`docker-compose.yml`, `monitoring/grafana/...`, `.gitignore` 등) 모두 실제 코드베이스에 존재 확인됨
- [x] "적절히 수정" 류의 모호한 지시 없음 — 각 태스크가 정확한 uid/panel id/엔드포인트를 명시
- [x] 이슈 #168과 무관한 파일(`07-order-tps.js`, `application-perf.yml`)을 건드리지 않도록 각 태스크의 금지 사항에 명시
