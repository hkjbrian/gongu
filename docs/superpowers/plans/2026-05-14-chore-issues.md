# Chore Issues #66 / #65 / #67 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 세 개의 chore 이슈를 순서대로 처리한다. #66 → #65 → #67

**Spec:** `docs/superpowers/specs/2026-05-14-chore-issues-design.md`

**Tech Stack:** Spring Boot 3.5, Java 25, Gradle, GitHub CLI (`gh`)

---

## 사전 작업: Milestone 연결

- [ ] 세 이슈에 milestone 연결 (공통 기반 = milestone #2)

```bash
gh issue edit 66 --repo hkjbrian/gongu --milestone 2
gh issue edit 65 --repo hkjbrian/gongu --milestone 2
gh issue edit 67 --repo hkjbrian/gongu --milestone 2
```

---

# Part 1 — Issue #66: 파일이 존재하는 디렉토리의 `.gitkeep` 삭제

### Task 1: 브랜치 생성 + .gitkeep 삭제 + 커밋 + PR

**참고 문서/파일:**
- Spec: `docs/superpowers/specs/2026-05-14-chore-issues-design.md` — "Issue #66" 섹션
- CLAUDE.md — 브랜치명 컨벤션, 커밋 형식, PR 형식

**수정 대상 파일:**
- Delete: 아래 목록의 `.gitkeep` 16개

**금지 사항:**
- `notification/`, `order/`, `payment/` 하위 `.gitkeep` — 아직 빈 디렉토리이므로 유지
- `store/domain/`, `user/domain/`, `user/dto/`, `user/service/` 하위 `.gitkeep` — 아직 빈 디렉토리이므로 유지

**구현 방향:**
- 파일이 존재하는 디렉토리의 `.gitkeep`만 삭제. 아래 경로를 `git rm`으로 제거

```
domain/auth/controller/.gitkeep
domain/auth/dto/.gitkeep
domain/auth/service/.gitkeep
domain/product/controller/.gitkeep
domain/product/domain/.gitkeep
domain/product/dto/.gitkeep
domain/product/repository/.gitkeep
domain/product/service/.gitkeep
domain/store/controller/.gitkeep
domain/store/dto/.gitkeep
domain/store/repository/.gitkeep
domain/store/service/.gitkeep
domain/user/controller/.gitkeep
domain/user/repository/.gitkeep
global/exception/.gitkeep
global/security/.gitkeep
```

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] 브랜치 생성

```bash
git checkout main && git pull
git checkout -b chore/#66-remove-gitkeep
```

- [ ] .gitkeep 삭제 후 검증 통과 확인

- [ ] 커밋

```bash
git commit -m "chore: 파일이 존재하는 디렉토리의 .gitkeep 삭제 (#66)"
```

- [ ] push + PR 생성

```bash
git push -u origin chore/#66-remove-gitkeep
gh pr create \
  --repo hkjbrian/gongu \
  --title "[CHORE] 파일이 존재하는 디렉토리의 .gitkeep 삭제 (#66)" \
  --body $'## 작업 내용\n- 파일이 존재하는 디렉토리의 불필요한 `.gitkeep` 삭제 (16개)\n- 빈 디렉토리(`notification/`, `order/`, `payment/` 등)는 유지\n\nclose #66' \
  --milestone 2
```

- [ ] PR 생성 후 CLAUDE.md 9~11단계(Codex PR 리뷰 위임 → Claude 판정 → 반영) 따름

---

# Part 2 — Issue #65: Member→User 네이밍 통일 + 패키지 구조 정리

> **전제 조건:** #66 PR merge 후 main 최신화

### Task 1: 브랜치 생성

- [ ] 브랜치 생성

```bash
git checkout main && git pull
git checkout -b chore/#65-unify-naming-user
```

---

### Task 2: product/domain/ → product/entity/ 패키지 이동

**참고 문서/파일:**
- Spec: `docs/superpowers/specs/2026-05-14-chore-issues-design.md` — "Issue #65 / 패키지 구조 변경" 섹션
- `src/main/java/com/gongu/server/domain/product/domain/Product.java` — 현재 내용 파악
- `src/main/java/com/gongu/server/domain/product/domain/ProductStatus.java`
- `src/main/java/com/gongu/server/domain/store/entity/` — entity/ 패키지 구조 참고 (표준)

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/product/entity/Product.java`
- Create: `src/main/java/com/gongu/server/domain/product/entity/ProductStatus.java`
- Delete: `src/main/java/com/gongu/server/domain/product/domain/Product.java`
- Delete: `src/main/java/com/gongu/server/domain/product/domain/ProductStatus.java`

**금지 사항:**
- Product.java 내부 로직 변경 금지 — 패키지 선언과 import만 변경
- `domain/product/domain/.gitkeep` — #66에서 이미 삭제됨. 별도 처리 불필요

**구현 방향:**
- 두 파일을 `entity/` 패키지로 이동. `package` 선언을 `com.gongu.server.domain.product.entity`로 변경
- `Product.java`의 `import com.gongu.server.domain.product.domain.ProductStatus` → `product.entity.ProductStatus`로 변경

**검증:**
```bash
./gradlew compileJava
```
Expected: 이 시점에서는 ProductRepository/ProductService의 import가 깨져 FAIL 가능 — 다음 태스크에서 수정

- [ ] 파일 이동 및 패키지 선언 수정

---

### Task 3: Member → User 엔티티·리포지토리 통일

**참고 문서/파일:**
- Spec: `docs/superpowers/specs/2026-05-14-chore-issues-design.md` — "네이밍 변경" 표
- `src/main/java/com/gongu/server/domain/user/entity/Member.java` — 현재 내용
- `src/main/java/com/gongu/server/domain/user/entity/UserSocial.java` — member 필드 확인
- `src/main/java/com/gongu/server/domain/user/repository/MemberRepository.java`
- `docs/schema/ddl.sql` — `users` 테이블 컬럼명 기준 (`@Table`, `@Column` 검증용)

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/user/entity/User.java`
- Create: `src/main/java/com/gongu/server/domain/user/repository/UserRepository.java`
- Modify: `src/main/java/com/gongu/server/domain/user/entity/UserSocial.java`
- Delete: `src/main/java/com/gongu/server/domain/user/entity/Member.java`
- Delete: `src/main/java/com/gongu/server/domain/user/repository/MemberRepository.java`

**금지 사항:**
- `@Table(name = "users")` 변경 금지 — DDL 물리명 유지
- User 클래스 내부 비즈니스 로직 변경 금지 — rename만

**구현 방향:**
- `Member` → `User`로 클래스명 변경 (패키지 선언, 파일명 포함)
- `MemberRepository` → `UserRepository` (제네릭 타입 `Member` → `User`)
- `UserSocial.java`: `private Member member` 필드 → `private User user`, 팩토리 메서드 파라미터도 동일하게 변경. `getMember()` → Lombok이 자동 생성하므로 필드명만 변경하면 됨

- [ ] 파일 생성/수정/삭제

---

### Task 4: MemberStore → UserStore + MemberStoreRepository → UserStoreRepository

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/store/entity/MemberStore.java` — 현재 내용
- `src/main/java/com/gongu/server/domain/store/repository/MemberStoreRepository.java`
- `docs/schema/ddl.sql` — `member_stores` 테이블 구조 (테이블명·제약명은 DDL 기준 유지)

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/store/entity/UserStore.java`
- Create: `src/main/java/com/gongu/server/domain/store/repository/UserStoreRepository.java`
- Delete: `src/main/java/com/gongu/server/domain/store/entity/MemberStore.java`
- Delete: `src/main/java/com/gongu/server/domain/store/repository/MemberStoreRepository.java`

**금지 사항:**
- `@Table(name = "member_stores")` 변경 금지 — DDL 물리명 유지
- `UQ_MEMBER_STORES_USER_STORE` 제약명 변경 금지 — DDL 기준
- JPQL 쿼리 로직 변경 금지 — 클래스명·파라미터명만 변경

**구현 방향:**
- `MemberStore` → `UserStore`, `private Member member` → `private User user`
- 팩토리 메서드 `create(Member member, ...)` → `create(User user, ...)`
- Repository JPQL에서 `MemberStore` → `UserStore`, `ms.member` → `ms.user`, 메서드 파라미터 `Member member` → `User user`
- `findAllByMember` → `findAllByUser`, `findByMemberAndStore` → `findByUserAndStore` 등 메서드명 변경

- [ ] 파일 생성/수정/삭제

---

### Task 5: DTO 리네임

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/store/dto/request/RegisterMemberStoreRequest.java`
- `src/main/java/com/gongu/server/domain/store/dto/response/RegisterMemberStoreResponse.java`
- `src/main/java/com/gongu/server/domain/store/dto/response/AdminMemberResponse.java`

**수정 대상 파일:**
- Create: `...dto/request/RegisterUserStoreRequest.java`
- Create: `...dto/response/RegisterUserStoreResponse.java`
- Create: `...dto/response/AdminUserResponse.java`
- Delete: `...dto/request/RegisterMemberStoreRequest.java`
- Delete: `...dto/response/RegisterMemberStoreResponse.java`
- Delete: `...dto/response/AdminMemberResponse.java`

**금지 사항:**
- record 내부 필드명·검증 로직 변경 금지 (`memberId` 필드는 `userId`로 변경해도 됨)
- `from(MemberStore ...)` → `from(UserStore ...)`로 파라미터 타입 변경 필요

**구현 방향:**
- 세 파일을 User 계열로 rename. `from()` 정적 팩토리의 파라미터 타입을 `UserStore`로 변경
- `AdminMemberResponse`의 `memberId` 필드 → `userId`로 변경 (DDL `user_id` 컬럼 기준)
- `RegisterMemberStoreResponse.from(MemberStore)` → `RegisterUserStoreResponse.from(UserStore)`, `getStore()/isPreferred()` 등 getter는 동일

- [ ] 파일 생성/삭제

---

### Task 6: 서비스·컨트롤러 레이어 import 업데이트

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/store/service/StoreService.java`
- `src/main/java/com/gongu/server/domain/store/service/StoreAdminService.java`
- `src/main/java/com/gongu/server/domain/product/service/ProductService.java`
- `src/main/java/com/gongu/server/domain/auth/service/AuthService.java`
- `src/main/java/com/gongu/server/domain/user/controller/MemberController.java`
- `src/main/java/com/gongu/server/domain/product/controller/MemberProductController.java`
- `src/main/java/com/gongu/server/domain/store/controller/StoreAdminController.java`

**수정 대상 파일:**
- Modify: 위 7개 파일 전체
- Create: `...user/controller/UserController.java` (MemberController 대체)
- Create: `...product/controller/UserProductController.java` (MemberProductController 대체)
- Delete: `...user/controller/MemberController.java`
- Delete: `...product/controller/MemberProductController.java`

**금지 사항:**
- API 엔드포인트 URL 경로 변경 금지 (`/members/me/stores` 등 — URL은 이번 이슈 범위 밖)
- 비즈니스 로직 변경 금지 — import와 타입 참조만 변경

**구현 방향:**
- 각 파일에서 `Member` → `User`, `MemberRepository` → `UserRepository`, `MemberStore` → `UserStore`, `MemberStoreRepository` → `UserStoreRepository`, `RegisterMemberStoreRequest/Response` → `RegisterUserStoreRequest/Response`, `AdminMemberResponse` → `AdminUserResponse`
- `product.domain.*` import → `product.entity.*`로 변경
- `AuthService`: `UserSocial::getMember` → `UserSocial::getUser`, `Member.of()` → `User.of()`, `memberRepository` → `userRepository`
- `StoreService`: `registerMemberStore()` → `registerUserStore()` 메서드명 변경 (서비스 내부 메서드)

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] 모든 파일 수정 후 컴파일 검증

---

### Task 7: 커밋 분리 (레이어별)

**참고 문서/파일:**
- CLAUDE.md — 커밋 단위 규칙 ("엔티티/리포지토리", "컨트롤러+DTO" 분리)

- [ ] Commit 1 — 엔티티·리포지토리

```bash
git add \
  src/main/java/com/gongu/server/domain/product/entity/ \
  src/main/java/com/gongu/server/domain/product/repository/ \
  src/main/java/com/gongu/server/domain/user/entity/ \
  src/main/java/com/gongu/server/domain/user/repository/ \
  src/main/java/com/gongu/server/domain/store/entity/UserStore.java \
  src/main/java/com/gongu/server/domain/store/repository/UserStoreRepository.java
git commit -m "chore: Member 엔티티·리포지토리 User 로 통일, product 패키지 domain→entity (#65)"
```

- [ ] Commit 2 — 컨트롤러·DTO·서비스

```bash
git add \
  src/main/java/com/gongu/server/domain/store/dto/ \
  src/main/java/com/gongu/server/domain/store/service/ \
  src/main/java/com/gongu/server/domain/store/controller/ \
  src/main/java/com/gongu/server/domain/product/controller/ \
  src/main/java/com/gongu/server/domain/product/service/ \
  src/main/java/com/gongu/server/domain/user/controller/ \
  src/main/java/com/gongu/server/domain/auth/service/
git commit -m "chore: Member 컨트롤러·DTO·서비스 User 로 통일 (#65)"
```

---

### Task 8: 테스트 코드 업데이트

**참고 문서/파일:**
- `src/test/java/com/gongu/server/domain/user/controller/MemberControllerTest.java`
- `src/test/java/com/gongu/server/domain/store/repository/MemberStoreRepositoryTest.java`
- `src/test/java/com/gongu/server/domain/store/service/StoreServiceTest.java`
- `src/test/java/com/gongu/server/domain/store/service/StoreAdminServiceTest.java`
- `src/test/java/com/gongu/server/domain/store/controller/StoreAdminControllerTest.java`
- `src/test/java/com/gongu/server/domain/store/controller/StoreControllerTest.java`
- `src/test/java/com/gongu/server/domain/product/domain/ProductTest.java`
- `src/test/java/com/gongu/server/domain/product/service/ProductStockConcurrencyTest.java`

**수정 대상 파일:**
- Create: `...user/controller/UserControllerTest.java` (MemberControllerTest 대체)
- Create: `...store/repository/UserStoreRepositoryTest.java` (MemberStoreRepositoryTest 대체)
- Modify: 위 나머지 6개 테스트 파일
- Delete: `MemberControllerTest.java`, `MemberStoreRepositoryTest.java`

**금지 사항:**
- 테스트 로직·시나리오 변경 금지 — import와 타입 참조만 변경

**구현 방향:**
- Task 6과 동일한 rename 패턴을 테스트 코드에 적용
- `MemberControllerTest`: `@WebMvcTest(MemberController.class)` → `@WebMvcTest(UserController.class)`
- `MemberStoreRepositoryTest`: `Member.of(...)`, `MemberStore.create(...)` → `User.of(...)`, `UserStore.create(...)`
- `product` 패키지 테스트: `import ...product.domain.*` → `import ...product.entity.*`

**검증:**
```bash
./gradlew compileTestJava
```
Expected: BUILD SUCCESSFUL

- [ ] 테스트 파일 수정 후 컴파일 검증

- [ ] Commit 3 — 테스트

```bash
git add src/test/
git commit -m "chore: 테스트 코드 Member → User 네이밍 통일 (#65)"
```

---

### Task 9: 문서 업데이트

**참고 문서/파일:**
- Spec: `docs/superpowers/specs/2026-05-14-chore-issues-design.md` — "문서 수정 대상" 목록

**수정 대상 파일:**
- `docs/schema/table-definitions.md`
- `docs/api/openapi.yaml`
- `docs/adr/아키텍처_및_코드_컨벤션.md`
- `docs/adr/예외_처리_전략.md`
- `docs/adr/인증_방식_선택.md`
- `docs/adr/재고_동시성_제어_전략.md`
- `docs/00-project-brief.md`
- `docs/01-requirements.md`
- `docs/02-domain-rules.md`

**금지 사항:**
- DDL 물리명 변경 금지: `member_stores` 테이블명, `UQ_MEMBER_STORES_USER_STORE` 제약명

**구현 방향:**
- 각 문서에서 Java 엔티티/클래스 개념으로 쓰인 `Member` → `User`로 교체
- `MemberController`, `MemberRepository` 등 클래스명 언급 → 대응하는 `User*` 클래스명으로 교체
- `AdminMemberResponse` → `AdminUserResponse`, `RegisterMemberStoreRequest/Response` → `RegisterUserStoreRequest/Response`

- [ ] 문서 수정 후 커밋

```bash
git add docs/
git commit -m "chore: 문서 Member → User 네이밍 통일 (#65)"
```

---

### Task 10: push + PR 생성

- [ ] push

```bash
git push -u origin chore/#65-unify-naming-user
```

- [ ] PR 생성

```bash
gh pr create \
  --repo hkjbrian/gongu \
  --title "[CHORE] DDL 기준 네이밍 통일 (Member→User) + 패키지 구조 정리 (#65)" \
  --body $'## 작업 내용\n- `domain/product/domain/` → `domain/product/entity/` 패키지 이동\n- `Member` 엔티티·리포지토리·컨트롤러·DTO → `User` 계열로 통일\n- `MemberStore`/`MemberStoreRepository` → `UserStore`/`UserStoreRepository`\n- 이미 올바른 이름 유지: `UserSocial`, `UserSocialRepository`, `UserPrincipal`, `UserErrorCode`\n- 관련 문서 전체 반영\n\n## 참고사항\n- DB 테이블명(`member_stores`)·제약명은 DDL 물리 이름이므로 변경 없음\n- API 엔드포인트 경로(`/members/me/stores` 등)는 변경 없음\n\nclose #65' \
  --milestone 2
```

- [ ] PR 생성 후 CLAUDE.md 9~11단계(Codex PR 리뷰 위임 → Claude 판정 → 반영) 따름

---

# Part 3 — Issue #67: email 필드 optional 처리 + email-availability API 제거

> **전제 조건:** #65 PR merge 후 main 최신화. 이 시점에서 엔티티는 이미 `User`로 rename된 상태.

### Task 1: 브랜치 생성

- [ ] 브랜치 생성

```bash
git checkout main && git pull
git checkout -b chore/#67-email-optional
```

---

### Task 2: DDL + User 엔티티 변경

**참고 문서/파일:**
- Spec: `docs/superpowers/specs/2026-05-14-chore-issues-design.md` — "Issue #67" 섹션
- `docs/schema/ddl.sql` — `users` 테이블 현재 상태
- `src/main/java/com/gongu/server/domain/user/entity/User.java` — email 컬럼 어노테이션

**수정 대상 파일:**
- Modify: `docs/schema/ddl.sql`
- Modify: `src/main/java/com/gongu/server/domain/user/entity/User.java`

**금지 사항:**
- `store_admins.email`의 UNIQUE/NOT NULL 변경 금지 — `users.email`만 해당
- User 엔티티의 다른 필드 변경 금지

**구현 방향:**
- `ddl.sql`: `users.email` 컬럼 `NOT NULL` → `NULL`, `UQ_USERS_EMAIL` UNIQUE 제약 라인 삭제
- `User.java`: `@Column(nullable = false, unique = true)` → `@Column(nullable = true)`, `of()` 팩토리에서 `email` 파라미터 제거

**검증:**
```bash
./gradlew compileJava
```
Expected: 이 시점에서 AuthService가 `User.of(name, email, phone)` 3인자 호출 → FAIL 가능. 다음 태스크에서 수정.

- [ ] DDL + 엔티티 수정

- [ ] Commit 1

```bash
git add docs/schema/ddl.sql src/main/java/com/gongu/server/domain/user/entity/User.java
git commit -m "chore: users.email NOT NULL 및 UNIQUE 제약 제거 (#67)"
```

---

### Task 3: AuthService 수정 + email-availability API 제거

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/auth/service/AuthService.java` — `registerKakaoUser()`, `checkEmailAvailability()` 메서드
- `src/main/java/com/gongu/server/domain/auth/controller/AuthController.java` — `checkEmailAvailability` 엔드포인트
- `src/main/java/com/gongu/server/domain/auth/dto/response/EmailAvailabilityResponse.java`

**수정 대상 파일:**
- Modify: `AuthService.java`
- Modify: `AuthController.java`
- Delete: `EmailAvailabilityResponse.java`

**금지 사항:**
- `kakaoLogin()`, `storeAdminLogin()`, `refreshToken()` 로직 변경 금지
- `AuthController`의 다른 엔드포인트 변경 금지

**구현 방향:**
- `AuthService.registerKakaoUser()`: 이메일 폴백 로직(`kakao-{id}@noemail.local`) 전체 제거. `User.of()` 호출에서 email 인자 제거
- `AuthService.checkEmailAvailability()` 메서드 전체 삭제, `EmailAvailabilityResponse` import 삭제
- `AuthController`: `GET /auth/email-availability` 엔드포인트 메서드 전체 삭제, 관련 import(`@Email`, `@NotBlank`, `@Validated`, `EmailAvailabilityResponse`) 삭제. `@Validated`가 다른 곳에 쓰이지 않으면 클래스 어노테이션도 제거

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] 코드 수정 후 컴파일 검증

---

### Task 4: 테스트 코드 업데이트

**참고 문서/파일:**
- `src/test/` 전체에서 `User.of(` 패턴 검색

```bash
grep -rn "User\.of(" src/test/
```

**구현 방향:**
- `User.of(name, email, phone)` 3인자 호출 → `User.of(name, phone)` 2인자로 변경 (email 인자 제거)

**검증:**
```bash
./gradlew compileTestJava
```
Expected: BUILD SUCCESSFUL

- [ ] 테스트 수정 후 커밋

```bash
git add src/main/java/com/gongu/server/domain/auth/ src/test/
git commit -m "chore: email-availability API 및 관련 로직 제거 (#67)"
```

---

### Task 5: 문서 업데이트 + push + PR 생성

**수정 대상 파일:**
- `docs/schema/table-definitions.md` — `users.email` 설명에서 NOT NULL / UNIQUE 제거
- `docs/api/openapi.yaml` — `GET /auth/email-availability` 엔드포인트 섹션 삭제

- [ ] 문서 수정 후 커밋

```bash
git add docs/schema/table-definitions.md docs/api/openapi.yaml
git commit -m "chore: email optional 처리 반영, email-availability API 문서 제거 (#67)"
```

- [ ] push + PR 생성

```bash
git push -u origin chore/#67-email-optional
gh pr create \
  --repo hkjbrian/gongu \
  --title "[CHORE] email 필드 optional 처리, email-availability API 제거 (#67)" \
  --body $'## 작업 내용\n- `users.email` DDL: NOT NULL 제거, UNIQUE 제약 제거\n- `User.email`: nullable 변경, `of()` 팩토리 email 파라미터 제거\n- 카카오 OAuth 가입 시 이메일 폴백 로직 제거\n- `GET /auth/email-availability` 엔드포인트 및 `EmailAvailabilityResponse` DTO 삭제\n- 관련 문서 반영\n\n## 참고사항\n- 카카오 비즈니스 앱 등록 완료 시 email 수집 여부 재검토 예정\n\nclose #67' \
  --milestone 2
```

- [ ] PR 생성 후 CLAUDE.md 9~11단계(Codex PR 리뷰 위임 → Claude 판정 → 반영) 따름
