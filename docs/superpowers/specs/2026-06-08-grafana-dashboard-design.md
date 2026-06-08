# Grafana 대시보드 설계 — Gongu Service Overview

## 목적

비즈니스 플로우(주문→결제→만료) 디버깅을 위한 단일 Grafana 대시보드 구성.
문제 발생 시 비즈니스 지표 이상 → DB 락 / 인프라 원인으로 빠르게 좁혀가는 흐름을 제공한다.

---

## 변경 파일

| 파일 | 변경 내용 |
|------|----------|
| `monitoring/grafana/dashboards/gongu-dashboard.json` | 신규 생성 |
| `docker-compose.yml` | grafana 서비스에 dashboards 볼륨 마운트 추가 |

---

## 대시보드 구조

대시보드 이름: **Gongu Service Overview**
데이터소스: Prometheus (프로비저닝된 기본 소스)
기본 시간 범위: Last 1 hour

### Row 1 — 비즈니스 플로우 Overview

주문 생성 → 결제 완료 → 결제 실패 → 주문 만료 흐름을 왼쪽에서 오른쪽으로 배치.
각 패널은 Stat 시각화, 1분 rate 기준.

| 패널 제목 | PromQL | 시각화 |
|----------|--------|--------|
| 주문 생성률 (req/min) | `rate(gongu_order_created_total[1m]) * 60` | Stat |
| 결제 완료률 (req/min) | `rate(gongu_payment_completed_total[1m]) * 60` | Stat |
| 결제 실패률 (req/min) | `rate(gongu_payment_failed_total[1m]) * 60` | Stat (임계 >0 → 빨간색) |
| 주문 만료률 (req/min) | `rate(gongu_order_expired_total[1m]) * 60` | Stat |

### Row 2 — 결제 실패 상세

실패 원인별 breakdown. 어떤 reason이 급증하는지 파악.

| 패널 제목 | PromQL | 시각화 |
|----------|--------|--------|
| 결제 실패 사유별 추이 | `rate(gongu_payment_failed_total[1m]) * 60` by `reason` | Time series (스택) |
| 결제 실패 사유 비율 | `increase(gongu_payment_failed_total[1h])` by `reason` | Pie chart |

reason 값 6종: `order_expired_idempotent`, `order_expired_cancel`, `pg_error`, `pg_null_response`, `pg_status_mismatch`, `amount_mismatch`

### Row 3 — DB 락 & 스케줄러 성능

락 대기 시간 급증은 비즈니스 지연의 직접 원인. 스케줄러 실행 시간으로 만료 처리 부하 파악.

| 패널 제목 | PromQL | 시각화 |
|----------|--------|--------|
| Order 락 쿼리 시간 | `histogram_quantile(0.5\|0.95\|0.99, rate(gongu_db_lock_query_duration_seconds_bucket{entity="order"}[1m]))` | Time series (3개 라인) |
| Product 락 쿼리 시간 | 동일, `entity="product"` | Time series (3개 라인) |
| 만료 스케줄러 실행 시간 | `histogram_quantile(0.95, rate(gongu_order_expire_duration_seconds_bucket[5m]))` | Time series |

### Row 4 — JVM / HTTP / HikariCP

인프라 이상이 비즈니스 지표에 영향을 주는지 확인.

| 패널 제목 | PromQL | 시각화 |
|----------|--------|--------|
| JVM 힙 사용량 | `jvm_memory_used_bytes{area="heap"}` | Time series |
| HTTP 요청률 & 5xx 에러율 | `rate(http_server_requests_seconds_count[1m])` by `status` | Time series |
| HTTP 응답 시간 p95 | `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[1m]))` | Time series |
| HikariCP 활성 커넥션 | `hikaricp_connections_active` | Gauge |

---

## docker-compose 변경

grafana 서비스 `volumes`에 아래 1줄 추가:

```yaml
- ./monitoring/grafana/dashboards:/var/lib/grafana/dashboards:ro
```

기존 `dashboard.yml`의 `path: /var/lib/grafana/dashboards` 설정과 일치.

---

## 완료 기준

- `docker compose up -d prometheus grafana` 후 Grafana(`http://localhost:3001`) 접속
- Gongu 폴더에 "Gongu Service Overview" 대시보드 자동 표시
- 4개 Row 패널 전부 데이터 로딩 (서버 기동 상태 기준)
