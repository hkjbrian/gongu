# 부하테스트 결과 기록 자동화 설계

**작성일**: 2026-07-03
**대상**: k6 부하테스트 실행 → Grafana 스크린샷 캡처 → Notion 기록 파이프라인 자동화

---

## 1. 목표

지금까지는 k6를 수동 실행하고, 터미널에 출력된 결과 텍스트를 복사하고, Grafana 대시보드에서 해당 부하테스트 시간대를 직접 지정해 스크린샷을 여러 장 찍은 뒤, Notion 페이지("부하테스트 개선 진행")에 토글로 수동 정리해왔다. 이 전 과정을 명령어 하나로 자동화한다.

- k6 시나리오 실행부터 Notion 기록까지 원커맨드로 완료
- k6 threshold 통과/실패와 무관하게 항상 결과를 남긴다 (실패도 유의미한 데이터)
- Grafana 스크린샷은 해당 실행의 정확한 시간대만 캡처
- MySQL 지표를 포함해, 현재 레포에 커밋되지 않은 임포트 대시보드도 재현 가능하게 정리

---

## 2. 전체 데이터 흐름

```
python3 load-test/report/run_and_report.py <scenario> --condition "<설명>"
```

1. 실행 시작 시각(UTC epoch) 기록
2. `docker compose --profile k6 run --rm k6 run /scripts/scenarios/<scenario>.js` 서브프로세스 실행
   - stdout을 실시간으로 터미널에 흘려보내면서 동시에 버퍼에 캡처 (기존 수동 실행과 동일한 가시성 유지)
3. 프로세스 종료 시각 기록, exit code로 pass/fail 판정 (k6는 threshold 실패 시 non-zero exit)
4. **30초 대기** — Prometheus scrape_interval(15s)로 인해 테스트 종료 직후 스크린샷을 찍으면 마지막 구간 데이터가 비어보이는 문제 방지
5. Grafana Render API(`/render/d-solo/...`)로 지정된 패널 10장을 `from=시작시각&to=종료시각+버퍼` 범위로 PNG 캡처
6. Notion File Upload API로 PNG 10장 업로드, `file_upload_id` 획득
7. Notion 페이지(고정 `NOTION_PAGE_ID`) 최상위 블록 목록 맨 끝에 새 토글(`toggle` block)을 append
   - 토글 제목: `--condition`으로 받은 설명 + 자동 pass/fail 표시 (예: `2000 TPS 단일 실행, 웜업 이후 (o)`)
   - 토글 내용: k6 stdout 전체를 코드블록(`code` block, language=`plain text`)으로 먼저 넣고, 이어서 이미지 블록 10개를 순서대로 추가
8. 각 단계 결과를 콘솔에 진행 로그로 출력, 최종적으로 Notion 블록 URL 출력

번호("N차")는 스크립트가 붙이지 않는다. 사용자가 Notion에서 직접 편집해 붙인다.

---

## 3. Grafana 변경사항

### 3.1 Image Renderer 플러그인 추가

`docker-compose.yml`의 `grafana` 서비스에 플러그인 설치 및 렌더링 설정을 추가한다.

```yaml
grafana:
  environment:
    - GF_SECURITY_ADMIN_USER=${GRAFANA_USER:-admin}
    - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_PASSWORD:-admin}
    - GF_INSTALL_PLUGINS=grafana-image-renderer
```

플러그인 내장 렌더링 모드(별도 렌더러 컨테이너 없이 Grafana 프로세스가 플러그인을 서브프로세스로 구동해 headless Chromium 렌더링)를 사용하므로 `GF_RENDERING_SERVER_URL` 등 원격 렌더러용 설정은 불필요하다. 부하테스트 자체와 리소스가 경합하지 않는 시점(테스트 종료 후)에 실행되므로 서버 컨테이너(2vCPU/2GB)의 측정 결과에 영향을 주지 않는다.

### 3.2 레포에 없는 대시보드 커밋

현재 실제 Grafana 인스턴스에는 수동 import된 대시보드가 있으나 `monitoring/grafana/dashboards/`에는 커밋되어 있지 않다. 재현성을 위해 아래 3개를 JSON export하여 커밋하고 provisioning에 포함시킨다.

- `JVM (Micrometer)` (uid: `efoj0uvwhzq4gf`)
- `MySQL Exporter Quickstart and Dashboard` (uid: `549c2bf8936f7767ea6ac47c47b00f2a`)
- `Spring Boot 3.x Statistics` (uid: `spring_boot_21`)

`MySQL Simple Dashboard`(uid: `4Z1opp5mk`)는 리플리케이션(Slave) 중심 패널이 많아 단일 MySQL 인스턴스 구성과 맞지 않으므로 커밋하지 않는다.

### 3.3 기본 캡처 패널 세트 (총 10장, 스크립트 내 상수로 고정)

| 대시보드 | 패널 ID | 패널명 |
|---|---|---|
| Gongu Service Overview (`gongu-service-overview`) | 15 | HTTP 요청률 & 에러율 |
| Gongu Service Overview | 16 | HTTP 응답 시간 p95 |
| Gongu Service Overview | 17 | HikariCP 활성 커넥션 |
| Gongu Service Overview | 14 | JVM 힙 사용량 |
| Gongu Service Overview | 19 | Order 생성 전체 소요시간 |
| MySQL Exporter Quickstart (`549c2bf8936f7767ea6ac47c47b00f2a`) | 13 | Current QPS |
| MySQL Exporter Quickstart | 92 | MySQL Connections |
| MySQL Exporter Quickstart | 48 | MySQL Slow Queries |
| MySQL Exporter Quickstart | 51 | InnoDB Buffer Pool |
| MySQL Exporter Quickstart | 32 | MySQL Table Locks |

패널 세트는 스크립트 상단 상수 리스트로 관리하며, 추후 필요 시 목록만 수정하면 된다 (CLI 옵션화는 지금 범위에서 제외).

---

## 4. 인증 / 시크릿

`.env`에 다음을 추가한다 (기존 `GRAFANA_USER`/`GRAFANA_PASSWORD`는 재사용):

```
NOTION_TOKEN=secret_...   # Notion Internal Integration secret
NOTION_PAGE_ID=388e5691-46a6-8021-95f8-fe481ee7e586
```

- Grafana Render API는 기존 admin basic auth(`GRAFANA_USER`/`GRAFANA_PASSWORD`) 그대로 사용
- Notion 측에서는 사전에 KJ 워크스페이스에 Internal Integration을 생성하고, 대상 페이지("부하테스트 개선 진행")의 `...` 메뉴 → Connections에서 해당 Integration을 1회 수동 연결해둬야 한다. 이 설정은 스크립트 범위 밖의 1회성 수동 작업이다.

---

## 5. 스크립트 구성

- **언어**: Python 3
- **위치**: `load-test/report/run_and_report.py`, `load-test/report/requirements.txt`
- **주요 의존성**: `requests` (Grafana render, Notion REST 직접 호출), `python-dotenv` (.env 로드)
- **CLI**:
  ```
  python3 load-test/report/run_and_report.py <scenario-file-basename> --condition "<설명 텍스트>"
  ```
  예: `python3 load-test/report/run_and_report.py 07-order-tps --condition "2000 TPS 단일 실행, 웜업 이후"`

### 실패 처리

- k6 threshold 실패 → 스크립트는 계속 진행하며 Notion에 결과를 그대로 기록하고 토글 제목에 `(x)`를 붙인다. threshold 통과 시 `(o)`.
- Grafana 렌더링 또는 Notion API 호출이 실패하면(네트워크 오류, 인증 오류 등) 스크립트는 그 시점까지 확보한 k6 stdout과 (있다면) PNG를 `load-test/reports/<timestamp>/`에 로컬 백업 저장한 뒤 non-zero exit으로 종료한다. 재실행 없이 백업본으로 수동 복구 가능하도록 한다.
- `load-test/reports/`는 `.gitignore`에 추가한다 (로컬 백업 산출물이므로 커밋 대상 아님).

---

## 6. 변경/신규 파일 목록

- `docker-compose.yml` — grafana 서비스에 image-renderer 플러그인 설정 추가
- `monitoring/grafana/dashboards/jvm-micrometer.json` (신규, export)
- `monitoring/grafana/dashboards/mysql-exporter-quickstart.json` (신규, export)
- `monitoring/grafana/dashboards/spring-boot-3x-statistics.json` (신규, export)
- `load-test/report/run_and_report.py` (신규)
- `load-test/report/requirements.txt` (신규)
- `.env.example` — `NOTION_TOKEN`, `NOTION_PAGE_ID` 항목 추가 (실제 값은 `.env`에만, 커밋 금지)
- `.gitignore` — `load-test/reports/` 추가

---

## 7. 테스트 / 검증 방법

- 짧은 시나리오(예: 낮은 TPS의 `07-order-tps.js`)로 전체 파이프라인을 1회 end-to-end 실행하여 다음을 확인:
  - k6 stdout이 콘솔에 실시간 출력되고 캡처됨
  - Grafana에서 해당 시간대 패널 10장이 정상 캡처됨 (빈 그래프가 아님)
  - Notion 페이지 하단에 토글이 정상 생성되고 이미지가 모두 표시됨
  - threshold를 의도적으로 실패시키는 케이스(예: 매우 높은 TPS)로 `(x)` 표시 및 정상 기록 확인
  - Grafana/Notion 자격증명을 일부러 잘못 설정해 실패 시나리오에서 로컬 백업이 생성되는지 확인

---

## 8. 범위 제외

- 패널 세트를 CLI 옵션으로 동적 지정하는 기능
- "N차" 자동 번호 부여
- 시나리오별로 다른 패널 세트를 쓰는 기능
- Grafana 별도 렌더러 컨테이너 분리(현재는 플러그인 내장 렌더링으로 충분하다고 판단)
