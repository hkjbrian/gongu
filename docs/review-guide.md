# Gongu 코드 리뷰 가이드

> Codex 에이전트 및 리뷰어가 PR을 검토할 때 참조하는 기준 문서.
> 관련 ADR: [ADR-002 아키텍처·코드 컨벤션](adr/아키텍처_및_코드_컨벤션.md), [ADR-004 예외 처리 전략](adr/예외_처리_전략.md)

---

## 1. 아키텍처 / 레이어 책임 (ADR-002)

### Entity
- [ ] `public setter` 없음 — 상태 변경은 반드시 도메인 메서드를 통해
- [ ] `@Builder` 외부 노출 없음 → `@Builder(access = AccessLevel.PRIVATE)`
- [ ] 생성은 정적 팩토리 메서드(`create`, `of`, `initiate` 등)로만
- [ ] 도메인 규칙 위반 검증이 Entity 내부에서 수행됨 (Service에서 getter 꺼내 판단 금지)
- [ ] 두 엔티티 간 판단이 필요한 경우, 한 엔티티가 다른 엔티티를 파라미터로 받아 직접 판단
- [ ] `@Transactional` 사용 금지 (Service 전용)
- [ ] DB 조회·외부 API 호출 없음

### Service
- [ ] DTO를 반환함 (Entity를 Controller까지 노출하지 않음)
- [ ] 트랜잭션 경계(`@Transactional`)를 Service가 직접 관리
- [ ] `@Transactional(readOnly = true)` — 조회 전용 메서드에 적용되어 있는가
- [ ] 외부 API 호출 실패 → `InfraException`으로 감싸서 re-throw
- [ ] 리소스 미존재(404) → Repository 조회 후 Service에서 throw

### Controller
- [ ] `try-catch` 없음 (예외 처리는 GlobalExceptionHandler 전담)
- [ ] `@Valid` 로 DTO 입력값 검증
- [ ] Service가 반환한 DTO를 그대로 반환 (추가 변환 없음)
- [ ] HTTP 상태코드가 의미에 맞게 사용됨 (등록 201, 조회 200, 삭제 204 등)

### DTO
- [ ] Response DTO: `ResponseDTO.from(entity)` 정적 팩토리 패턴 사용
- [ ] `Entity.toXxx()` 형태의 변환 메서드 없음 (Entity가 DTO를 알면 안 됨)
- [ ] Request DTO → Entity 변환은 Service에서 Entity의 정적 팩토리 메서드를 직접 호출

---

## 2. JPA 매핑

- [ ] `@Table(name=...)`, `@Column(name=...)`, `@JoinColumn(name=...)` 이 `docs/schema/ddl.sql` 과 일치
- [ ] FK NOT NULL 컬럼의 `@ManyToOne(optional = false)` 설정
- [ ] 모든 연관관계에 `fetch = FetchType.LAZY`
- [ ] 소프트 딜리트 조회: `deletedAtIsNull` 조건이 Repository 쿼리에 포함
- [ ] `@GeneratedValue(strategy = GenerationType.IDENTITY)` 사용 (MySQL AUTO_INCREMENT 기준)
- [ ] JPA Auditing: `@CreatedDate`, `@LastModifiedDate` 적절히 사용, `@EntityListeners(AuditingEntityListener.class)` 누락 없음

### N+1 문제 점검
- [ ] 컬렉션 조회 후 반복문 내에서 연관 엔티티를 추가 조회하지 않는가
- [ ] 필요한 경우 `fetch join` 또는 `@EntityGraph` 적용 여부 검토
- [ ] `@Transactional` 범위 내에서 Lazy Loading이 완료되는가 (트랜잭션 밖에서 Lazy 접근 없음)

---

## 3. 예외 처리 (ADR-004)

- [ ] ErrorCode 형식: `{도메인약어}_{3자리숫자}` (예: `STORE_001`, `USER_002`)
- [ ] 도메인 규칙 위반 → `BusinessException` (4xx)
- [ ] 외부 시스템 장애 → `InfraException` (5xx)
- [ ] `GlobalExceptionHandler` 이외의 레이어에서 예외를 직접 catch하여 응답으로 변환하지 않음
- [ ] 예외 메시지에 스택 트레이스·내부 구현 정보가 클라이언트에 노출되지 않음

---

## 4. 보안

- [ ] 인증이 필요한 API에 `@PreAuthorize` 또는 SecurityConfig에 접근 제어 설정
- [ ] 권한 검사: `ROLE_MEMBER`, `ROLE_STORE_ADMIN` 등 역할이 올바르게 적용
- [ ] `@AuthenticationPrincipal`로 가져온 사용자 ID와 요청 대상 리소스 소유자가 일치하는지 검증 (본인 데이터만 접근)
- [ ] SQL Injection 위험 없음 — 파라미터 바인딩 사용, 문자열 조합 쿼리 없음
- [ ] 민감 정보(비밀번호, 토큰 등)가 응답 DTO에 포함되지 않음
- [ ] 로그에 민감 정보가 출력되지 않음

---

## 5. 성능

- [ ] 페이지네이션이 필요한 목록 API에 `Pageable` 적용
- [ ] 인덱스가 있는 컬럼으로 조회 (`docs/schema/ddl.sql` 인덱스 섹션 참고)
- [ ] 불필요한 전체 컬럼 조회 없음 (필요한 경우 Projection 고려)
- [ ] 같은 데이터를 한 트랜잭션 내에서 여러 번 조회하지 않음

---

## 6. 트랜잭션

- [ ] 쓰기 작업에 `@Transactional` 적용, 조회 전용에 `@Transactional(readOnly = true)` 적용
- [ ] 트랜잭션 내에서 발생한 예외가 롤백을 유발하는가 (체크 예외의 경우 `rollbackFor` 설정 확인)
- [ ] Dirty Checking이 의도대로 동작하는가 (불필요한 `save()` 호출 또는 save() 누락)
- [ ] 외부 API 호출이 트랜잭션 내부에 포함되지 않도록 설계 (가능한 경우)

---

## 7. 테스트 품질

### 커버리지
- [ ] 정상 케이스(Happy Path) 테스트 존재
- [ ] 주요 예외 케이스(Not Found, Duplicate, 권한 없음 등) 테스트 존재
- [ ] 비즈니스 핵심 규칙(선호 매장 교체, 재고 차감 등)에 대한 테스트 존재

### 테스트 작성 품질
- [ ] `@DisplayName` 에 테스트 의도가 명확히 기술됨
- [ ] given/when/then 구조 명확
- [ ] 단순한 `assertNotNull` 이 아닌 실제 값 검증
- [ ] Mock 설정이 테스트 의도에 맞게 최소화됨 (과도한 mocking 없음)
- [ ] 테스트가 서로 독립적임 (다른 테스트의 실행 순서에 의존하지 않음)

### 테스트 레이어
- [ ] Repository: `@DataJpaTest` + H2 (MODE=MySQL), `@Import(JpaConfig.class)` 로 Auditing 활성화
- [ ] Service: `@ExtendWith(MockitoExtension.class)`, Repository를 `@Mock` 처리
- [ ] Controller: `@WebMvcTest` + `@AutoConfigureMockMvc(addFilters = false)`, Service를 `@MockBean` 처리

---

## 8. 코드 품질 / 가독성

- [ ] 메서드 하나가 하나의 책임만 가짐 (20줄 초과 시 분리 검토)
- [ ] 변수·메서드명이 의도를 명확히 표현함 (축약어, 의미 없는 이름 없음)
- [ ] 매직 넘버·매직 문자열 없음 (상수 또는 enum으로 추출)
- [ ] 중복 코드 없음 (동일한 로직이 2곳 이상에 존재하면 추출)
- [ ] 불필요한 주석 없음 (코드 자체로 의도가 전달되어야 함; 복잡한 비즈니스 규칙은 주석 허용)
- [ ] `TODO` / `FIXME` 가 있다면 이슈로 등록되어 있는가

---

## 리뷰 판정 기준

| 판정 | 기준 |
|------|------|
| **APPROVE** | 모든 섹션 이상 없음 |
| **COMMENT** | Minor 이슈만 존재 (아키텍처·보안·트랜잭션 문제 없음) |
| **REQUEST_CHANGES** | Critical 이슈 존재 — 아키텍처 규칙 위반, 보안 취약점, 테스트 핵심 케이스 누락, N+1 문제 등 |

### Critical 이슈 예시
- Entity에 public setter 또는 외부 노출 `@Builder` 존재
- `@Transactional` 없이 쓰기 작업 수행
- 권한 검사 누락 (인증 없이 타인 데이터 접근 가능)
- 핵심 비즈니스 규칙(선호 매장 교체, 재고 차감 등)에 테스트 없음
- N+1 쿼리가 명백히 발생하는 구조

### Minor 이슈 예시
- 변수명 개선 제안
- 불필요한 주석
- 테스트 DisplayName 불명확
- 인덱스 미활용 (성능 영향 미미한 경우)
