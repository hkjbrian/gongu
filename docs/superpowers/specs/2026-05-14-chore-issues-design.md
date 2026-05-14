# Chore Issues Design: #66, #65, #67

**Date:** 2026-05-14  
**Issues:** [#66](https://github.com/hkjbrian/gongu/issues/66), [#65](https://github.com/hkjbrian/gongu/issues/65), [#67](https://github.com/hkjbrian/gongu/issues/67)  
**처리 순서:** #66 → #65 → #67

---

## Issue #66 — 파일이 존재하는 디렉토리의 `.gitkeep` 삭제

### 배경
`.gitkeep`은 빈 디렉토리를 git에서 추적하기 위한 용도. 이미 파일이 존재하는 디렉토리에는 불필요.

### 삭제 대상
파일이 존재하는 디렉토리:
- `domain/auth/controller/`, `domain/auth/dto/`, `domain/auth/service/`
- `domain/product/controller/`, `domain/product/domain/`, `domain/product/dto/`, `domain/product/repository/`, `domain/product/service/`
- `domain/store/controller/`, `domain/store/dto/`, `domain/store/repository/`, `domain/store/service/`
- `domain/user/controller/`, `domain/user/repository/`
- `global/exception/`, `global/security/`

### 유지 대상
아직 빈 디렉토리 (미구현 도메인):
- `domain/notification/` 전체 하위
- `domain/order/` 전체 하위
- `domain/payment/` 전체 하위
- `domain/store/domain/`, `domain/user/domain/`, `domain/user/dto/`, `domain/user/service/`

### 커밋
- `chore: 파일이 존재하는 디렉토리의 .gitkeep 삭제 (#66)`

---

## Issue #65 — DDL 기준 네이밍 통일 (`Member → User`) + 패키지 구조 정리

### 배경
DDL 테이블명은 `users`인데 Java 코드에서 `Member`와 `User`가 혼용됨.
`domain/product/domain/` 서브패키지가 다른 도메인의 `entity/` 패턴과 불일치.

### 네이밍 변경 (`Member → User`)

| 현재 | 변경 후 | 위치 |
|------|---------|------|
| `Member` | `User` | `domain/user/entity/` |
| `MemberController` | `UserController` | `domain/user/controller/` |
| `MemberRepository` | `UserRepository` | `domain/user/repository/` |
| `MemberProductController` | `UserProductController` | `domain/product/controller/` |
| `MemberStore` | `UserStore` | `domain/store/entity/` |
| `MemberStoreRepository` | `UserStoreRepository` | `domain/store/repository/` |
| `RegisterMemberStoreRequest` | `RegisterUserStoreRequest` | `domain/store/dto/request/` |
| `RegisterMemberStoreResponse` | `RegisterUserStoreResponse` | `domain/store/dto/response/` |
| `AdminMemberResponse` | `AdminUserResponse` | `domain/store/dto/response/` |
| `MemberControllerTest` | `UserControllerTest` | `test/.../user/controller/` |
| `MemberStoreRepositoryTest` | `UserStoreRepositoryTest` | `test/.../store/repository/` |

**변경 없음** (이미 올바른 이름): `UserSocial`, `UserSocialRepository`, `UserPrincipal`, `UserErrorCode`

### 패키지 구조 변경

`domain/product/domain/` → `domain/product/entity/`

표준: `domain/{도메인명}/entity/`  
- `domain/store/entity/` ✓ (유지)
- `domain/user/entity/` ✓ (유지)
- `domain/product/entity/` (변경)

### 커밋 분리 (레이어별)

1. `chore: product 하위 패키지 domain → entity 로 변경 (#65)`
2. `chore: Member 엔티티·리포지토리 User 로 통일 (#65)`
3. `chore: Member 컨트롤러·DTO User 로 통일 (#65)`
4. `chore: 테스트 코드 Member → User 네이밍 통일 (#65)`
5. `chore: 문서 Member → User 네이밍 통일 (#65)`

### 문서 수정 대상
- `docs/schema/ddl.sql`
- `docs/schema/table-definitions.md`
- `docs/adr/API_문서화_방식_선택.md`
- `docs/adr/아키텍처_및_코드_컨벤션.md`
- `docs/adr/예외_처리_전략.md`
- `docs/adr/인증_방식_선택.md`
- `docs/adr/재고_동시성_제어_전략.md`
- `docs/api/openapi.yaml`
- `docs/00-project-brief.md`
- `docs/01-requirements.md`
- `docs/02-domain-rules.md`

---

## Issue #67 — `email` 필드 optional 처리 및 `email-availability` API 제거

### 배경
카카오 비즈니스 앱 등록 미완료로 OAuth에서 이메일 수집 불가.
현재 `AuthService.registerKakaoMember()`는 이미 `kakao-{id}@noemail.local` 폴백을 사용 중.
`email-availability` API는 사용 시나리오 없음.

### 코드 변경

**`User` 엔티티 (구 `Member`)**
- `@Column(nullable = false, unique = true)` → `@Column(nullable = true)`
- `User.of(name, email, phone)` → `User.of(name, phone)` (email 파라미터 제거)

**`AuthService`**
- `registerKakaoMember()`: 폴백 이메일 로직 제거, `User.of()` 호출 시 email 인자 제거

**삭제 대상**
- `AuthController.checkEmailAvailability()` 엔드포인트
- `AuthService.checkEmailAvailability()` 메서드
- `EmailAvailabilityResponse` DTO 클래스

### DDL 변경

```sql
-- 변경 전
`email` varchar(255) NOT NULL,
UNIQUE KEY `UQ_USERS_EMAIL` (`email`),

-- 변경 후
`email` varchar(255) NULL,
```

### 문서 수정 대상
- `docs/schema/ddl.sql` — email 컬럼 NOT NULL 제거, UNIQUE 제약 제거
- `docs/schema/table-definitions.md` — email 필드 설명 업데이트
- `docs/api/openapi.yaml` — `GET /auth/email-availability` 엔드포인트 제거

### 커밋 분리

1. `chore: users.email NOT NULL 및 UNIQUE 제약 제거, DDL 문서 업데이트 (#67)`
2. `chore: email-availability API 및 관련 로직 제거 (#67)`
