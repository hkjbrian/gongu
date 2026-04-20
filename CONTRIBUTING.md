# Contributing Guide

이 프로젝트는 개인 프로젝트이며, **GitHub Issue → 브랜치 생성 → 구현 → PR → Merge** 방식으로 관리됩니다.
Milestone이 Epic 역할을 합니다.

---

## 1. 브랜치 전략

### 형식

```
{type}/#{이슈번호}-{짧은-설명}
```

### 타입 목록

| 타입 | 설명 |
|------|------|
| `feat` | 새로운 기능 |
| `chore` | 환경 설정, 의존성, 빌드 |
| `fix` | 버그 수정 |
| `refactor` | 기능 변경 없는 코드 개선 |
| `docs` | 문서 |
| `test` | 테스트 코드 |

### 예시

```
feat/#10-base-entity
chore/#8-add-dependencies
fix/#23-order-cancel-status
```

### 규칙

- 설명은 영문 소문자 + 하이픈 사용
- 이슈 번호 반드시 포함
- `main` 브랜치에 직접 push 금지, 반드시 PR을 통해 merge

---

## 2. 이슈 컨벤션

### 형식

```
[TYPE] 작업 내용
```

### 타입 기준

| 타입 | 사용 기준 | 예시 |
|------|----------|------|
| FEAT | 새로운 기능/클래스 구현 | [FEAT] BaseEntity 구현 |
| CHORE | 환경 설정, 의존성, 빌드 관련 | [CHORE] build.gradle 의존성 추가 |
| REFACTOR | 기능 변경 없는 코드 개선 | [REFACTOR] 재고 차감 로직 엔티티로 이동 |
| FIX | 버그 수정 | [FIX] 결제 금액 불일치 수정 |
| DOCS | 문서 작업 | [DOCS] 테이블 정의서 작성 |
| TEST | 테스트 코드 | [TEST] OrderService 단위 테스트 |

### 규칙

- Milestone(Epic)에 반드시 연결
- 이슈 템플릿(작업 개요 / 작업 범위 / 완료 기준)을 따를 것

---

## 3. 커밋 메시지 컨벤션

### 형식

```
type: 작업 내용 (#이슈번호)
```

타입은 소문자 사용 (이슈 타입의 소문자 버전과 동일):
`feat`, `chore`, `fix`, `refactor`, `docs`, `test`

### 예시

```
feat: BaseEntity / SoftDeleteEntity 구현 (#10)
chore: build.gradle 의존성 추가 (#8)
fix: 주문 취소 상태 전이 오류 수정 (#23)
```

### 규칙

- 제목은 한국어 작성, 50자 이내
- 이슈 번호 반드시 포함
- 본문이 필요할 경우 빈 줄 후 작성

---

## 4. PR 컨벤션

### 형식

```
[TYPE] 작업 내용 (#이슈번호)
```

### 예시

```
[FEAT] BaseEntity / SoftDeleteEntity 구현 (#10)
[CHORE] build.gradle 의존성 추가 (#8)
```

### 규칙

- 이슈 번호를 PR 본문에 `close #이슈번호` 형태로 포함 (merge 시 이슈 자동 close)
- PR 템플릿(작업 내용 / 특이사항 / 참고사항)을 따를 것
- Milestone 연결 필수
