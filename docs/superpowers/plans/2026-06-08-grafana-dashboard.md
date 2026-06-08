# Grafana 대시보드 자동 프로비저닝 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Grafana 기동 시 "Gongu Service Overview" 대시보드가 자동으로 프로비저닝되도록 JSON 파일 추가 및 docker-compose 볼륨 마운트 연결

**Spec:** `docs/superpowers/specs/2026-06-08-grafana-dashboard-design.md`

**Tech Stack:** Grafana 11.1.0, Prometheus, Micrometer (Spring Boot Actuator)

---

## File Map

| 파일 | 변경 |
|------|------|
| `monitoring/grafana/dashboards/gongu-dashboard.json` | 신규 생성 |
| `docker-compose.yml` | grafana volumes에 dashboards 마운트 추가 |

**읽기 전용 참조:**
- `monitoring/grafana/provisioning/dashboards/dashboard.yml` — path 설정 확인
- `monitoring/grafana/provisioning/datasources/prometheus.yml` — 데이터소스 이름 확인
- `src/main/java/com/gongu/server/global/config/MetricsConfig.java` — 메트릭명 확인

---

## Task 1: docker-compose.yml — dashboards 볼륨 마운트 추가

**참고 문서/파일:**
- `docker-compose.yml` — 현재 grafana 서비스 volumes 구조 확인
- `monitoring/grafana/provisioning/dashboards/dashboard.yml` — `path: /var/lib/grafana/dashboards` 확인

**수정 대상 파일:**
- Modify: `docker-compose.yml`

**금지 사항:**
- grafana 서비스 외 다른 서비스(prometheus, redis, mysql) 변경 금지

**구현 방향:**
- grafana 서비스 `volumes` 블록에 아래 1줄 추가 (기존 두 줄 사이가 아닌 끝에):
  ```yaml
  - ./monitoring/grafana/dashboards:/var/lib/grafana/dashboards:ro
  ```
- 위치: `- grafana-data:/var/lib/grafana` 바로 앞에 삽입

**검증:**
```bash
grep "grafana/dashboards" docker-compose.yml
```
Expected: `- ./monitoring/grafana/dashboards:/var/lib/grafana/dashboards:ro` 출력

**커밋:** 다음 Task와 함께 하나의 커밋으로 처리

---

## Task 2: gongu-dashboard.json 생성

**참고 문서/파일:**
- `docs/superpowers/specs/2026-06-08-grafana-dashboard-design.md` — 전체 패널 목록 및 PromQL
- `monitoring/grafana/provisioning/datasources/prometheus.yml` — datasource name이 `Prometheus`임을 확인
- `src/main/java/com/gongu/server/global/config/MetricsConfig.java` — 메트릭명 확인

**수정 대상 파일:**
- Create: `monitoring/grafana/dashboards/gongu-dashboard.json`

**금지 사항:**
- `monitoring/grafana/provisioning/` 하위 파일 변경 금지 (이미 올바르게 설정됨)

**구현 방향:**

유효한 Grafana dashboard JSON을 생성한다. 아래 구조와 패널 목록을 반드시 포함할 것.

**상단 메타데이터:**
- `"title": "Gongu Service Overview"`
- `"uid": "gongu-service-overview"`
- `"schemaVersion": 38`
- `"timezone": "browser"`
- `"time": { "from": "now-1h", "to": "now" }`
- `"refresh": "30s"`
- 데이터소스: `{ "type": "prometheus", "uid": "${datasource}" }` + `"templating"` 변수로 datasource 선택 변수 추가

**Row 1 — 비즈니스 플로우 Overview (패널 4개, type: stat)**

| 패널 제목 | expr |
|----------|------|
| 주문 생성률 (건/분) | `rate(gongu_order_created_total[1m]) * 60` |
| 결제 완료률 (건/분) | `rate(gongu_payment_completed_total[1m]) * 60` |
| 결제 실패률 (건/분) | `rate(gongu_payment_failed_total[1m]) * 60` |
| 주문 만료률 (건/분) | `rate(gongu_order_expired_total[1m]) * 60` |

"결제 실패률" 패널에 threshold 설정: `0` 초과 시 빨간색(`#F2495C`), 기본 초록색(`#73BF69`).

**Row 2 — 결제 실패 상세 (패널 2개)**

| 패널 제목 | type | expr |
|----------|------|------|
| 결제 실패 사유별 추이 | timeseries | `rate(gongu_payment_failed_total[1m]) * 60`, legend: `{{reason}}`, stacking: normal |
| 결제 실패 사유 비율 | piechart | `increase(gongu_payment_failed_total[1h])`, legend: `{{reason}}` |

**Row 3 — DB 락 & 스케줄러 성능 (패널 3개, type: timeseries)**

| 패널 제목 | expr |
|----------|------|
| Order 락 쿼리 시간 | p50: `histogram_quantile(0.5, rate(gongu_db_lock_query_duration_seconds_bucket{entity="order"}[1m]))` / p95: `histogram_quantile(0.95, ...)` / p99: `histogram_quantile(0.99, ...)` — 3개 target, legend: `p50` / `p95` / `p99` |
| Product 락 쿼리 시간 | 동일, `entity="product"` |
| 만료 스케줄러 실행 시간 p95 | `histogram_quantile(0.95, rate(gongu_order_expire_duration_seconds_bucket[5m]))` |

단위: `s` (seconds)

**Row 4 — JVM / HTTP / HikariCP (패널 4개)**

| 패널 제목 | type | expr |
|----------|------|------|
| JVM 힙 사용량 | timeseries | `jvm_memory_used_bytes{area="heap"}`, 단위: bytes |
| HTTP 요청률 & 5xx 에러율 | timeseries | `rate(http_server_requests_seconds_count[1m])` by `status`, legend: `{{status}}` |
| HTTP 응답 시간 p95 | timeseries | `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[1m]))`, 단위: s |
| HikariCP 활성 커넥션 | gauge | `hikaricp_connections_active` |

**검증:**
```bash
# JSON 문법 검증
python3 -m json.tool monitoring/grafana/dashboards/gongu-dashboard.json > /dev/null && echo "JSON valid"

# 파일 존재 확인
ls -la monitoring/grafana/dashboards/
```
Expected: `JSON valid` 출력, 파일 존재

**커밋:**
```bash
git add monitoring/grafana/dashboards/gongu-dashboard.json docker-compose.yml
git commit -m "feat: Grafana 대시보드 자동 프로비저닝 추가 (#이슈번호)"
```

---

## Task 3: 동작 확인

**검증:**
```bash
# 컨테이너 재시작
docker compose down grafana && docker compose up -d grafana

# Grafana 헬스체크
sleep 5 && curl -s http://localhost:3001/api/health | python3 -m json.tool

# 대시보드 프로비저닝 확인 (admin/admin 기본 계정)
curl -s http://admin:admin@localhost:3001/api/dashboards/uid/gongu-service-overview | python3 -m json.tool | grep '"title"'
```
Expected: `"title": "Gongu Service Overview"` 포함된 응답

---

## GitHub 워크플로우

이슈 생성:
```bash
gh issue create --template feat-template.md \
  --title "Grafana 대시보드 자동 프로비저닝 추가" \
  --milestone 9
```

브랜치: `feat/#{이슈번호}-grafana-dashboard`

PR 생성 후 CLAUDE.md 리뷰 프로세스(Codex 리뷰 위임 → 판정 → 반영) 따름.
