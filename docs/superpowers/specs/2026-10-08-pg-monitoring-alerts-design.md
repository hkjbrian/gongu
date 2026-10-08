# PG 연동 모니터링 · 알림 규칙 설계

## 목적

PG(portone)가 느려지거나 멈췄을 때 **서버가 어떻게 버티고 있는지**, **결제가 어디에서 멈춰 있는지**를 대시보드에서 바로 보고, 사람이 봐야 하는 상태는 알림 규칙으로 잡는다.
기존 대시보드는 주문→결제→만료 흐름과 DB 락·JVM 지표만 있었고, 이미 구현된 Circuit Breaker·Retry·Bulkhead(#213, #214, #222)와 "운영자 확인 대상" 메트릭은 어디에도 보이지 않았다.

## 변경

| 구분 | 내용 |
|------|------|
| 메트릭 | `gongu.payment.pending`(미결 결제 수), `gongu.payment.pending.oldest.age`(가장 오래된 미결 결제 경과 초) — `PendingPaymentMetrics`, scrape 때마다 `payments.status` 인덱스로 조회. 조회 실패 시 NaN |
| 알림 규칙 | `monitoring/prometheus/alert-rules.yml` — 10개 (아래). `alert-rules.test.yml` 로 `promtool test rules` 검증 |
| 대시보드 | `gongu-dashboard.json` 에 row 3개 추가: 활성 알림 / PG 연동 / 미결 결제·운영자 확인 대상 |
| 버그 수정 | 기존 "주문 생성률" 패널 쿼리 `gongu_order_created_total` → `gongu_order_total` |
| 계약 테스트 | `MonitoringMetricNamesTest` — 대시보드·알림 규칙이 쓰는 메트릭 이름이 실제 Prometheus 출력에 있는지 고정 |

### "주문 생성률" 패널이 비어 있던 이유

Micrometer Counter `gongu.order.created` 는 Prometheus 클라이언트 1.x 에서 `_created` 접미사가 예약어라 `gongu_order_total` 로 노출된다.
기존 쿼리의 `gongu_order_created_total` 은 어떤 시리즈도 반환하지 않았다 (Prometheus `/api/v1/query` 로 0 series 확인).
카운터 이름을 바꾸지 않고 쿼리만 고쳤다. 다른 메트릭(`gongu_payment_completed_total` 등)은 영향 없음.

## 알림 규칙

| 알림 | 조건 | for | severity |
|------|------|-----|----------|
| GonguPendingPaymentStuck | 가장 오래된 PENDING 결제 > 15분 | 5m | warning |
| GonguPaymentReconcileExhausted | 만료 PG 조회 한도 초과(판정 불가) 증가 | - | critical |
| GonguPaymentFetchedUnderLock | 락 보유 중 PG 직접 조회 증가 | - | warning |
| GonguPaymentStateMismatch | 실패 사유 pg_status_mismatch / amount_mismatch 증가 | - | critical |
| GonguPgCircuitBreakerOpen | portone 서킷 OPEN | 1m | critical |
| GonguPgCallFailureRateHigh | 서킷 실패율 > 30% (임계 50% 전 단계) | 2m | warning |
| GonguPaymentBulkheadSaturated | payment-complete 여유 슬롯 0 | 1m | warning |
| GonguServerDown | gongu-server scrape 실패 | 1m | critical |
| GonguHikariConnectionsPending | HikariCP 대기 스레드 > 0 | 2m | warning |
| GonguHttp5xxRateHigh | 5xx 비율 > 5% (요청 0.1건/초 이상일 때) | 5m | warning |

- 임계값(15분, 30%, 5%)은 로컬 부하테스트 기준 초기값이다. 정상 상황의 baseline 데이터가 없어 임의로 좁히지 않는다 (#214 와 같은 원칙).
- 15분은 예약 TTL(10분) + 만료 스케줄러 판정 여유 5분.
- `failure_rate` 는 최소 호출 수(10건) 전에 -1 을 반환하므로 `> 30` 조건이 자연스럽게 제외한다 (`promtool` 테스트로 고정).

## 범위 밖

- **알림 전송(Alertmanager → Slack 등)은 연결하지 않았다.** 수신 채널·시크릿이 필요하다. 지금은 Prometheus `/alerts` 와 대시보드 "활성 알림" 패널(`ALERTS` 시계열)에서 확인한다.
- 운영 환경 모니터링 구성 — 이 구성은 로컬 Docker Compose 용이다.

## 검증

- 단위: `PendingPaymentMetricsTest`(게이지 값·NaN), `PaymentPendingQueryTest`(H2 쿼리), `MonitoringMetricNamesTest`(실제 스크랩에 메트릭 존재).
- `promtool check rules` / `promtool test rules` (Prometheus v2.53.0).
- 로컬 스택 전체(MySQL·Redis·앱·Prometheus·Grafana)를 띄워 확인: 오래된 PENDING 결제 12건을 심고 PG 주소를 연결 불가로 지정 → 만료 스케줄러의 PG 조회 실패로 서킷 OPEN, 알림 발화, 대시보드 각 패널 쿼리가 값을 반환.
