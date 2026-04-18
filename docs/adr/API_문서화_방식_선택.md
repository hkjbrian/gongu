# API 문서화 방식 선택

---

## 배경

API 명세서를 어떤 형식으로 작성하고 관리할지 결정해야 했다.  

빠른 개발을 위해서 AI(Codex, Claude 등)가 API 명세를 읽고 코드 생성에 활용할 수 있어야 하며, 지속적인 관리에 용이한 방식으로 선택하고자 했다.

---

## 고민한 선택지

### 1. Swagger (springdoc-openapi)

**장점**
- 의존성 하나로 Swagger UI 자동 생성
- 인터랙티브 테스트 기능 제공
- 초기 설정이 간단하다

**단점**
- 운영 코드에 `@Operation`, `@ApiResponse` 등 문서용 어노테이션이 침투한다
- 코드와 문서 어노테이션이 섞여 가독성이 저하된다
- 어노테이션 없이는 기본 정보만 제공되어 실용적이지 않다

### 2. OpenAPI YAML 직접 작성 (API First)

**장점**
- 코드 없이 명세 먼저 작성 가능
- AI에게 YAML을 주고 컨트롤러/DTO 생성 요청 가능
- 기계 친화적 구조로 파싱이 쉽다

**단점**
- 코드와 명세가 분리되어 있어 동기화를 수동으로 관리해야 한다
- 코드 변경 시 YAML도 함께 업데이트하지 않으면 불일치 발생

### 3. Spring REST Docs + restdocs-api-spec (채택)

**장점**
- 운영 코드에 문서용 코드가 전혀 침투하지 않는다
- 테스트를 반드시 작성해야 스니펫이 생성되므로 테스트 작성이 강제화된다
- 테스트가 통과해야 문서가 생성되므로 코드와 문서가 항상 일치한다
- `restdocs-api-spec` 플러그인으로 OAS(OpenAPI Specification) 변환이 가능하다
- 변환된 OAS로 Swagger UI 렌더링 → 인터랙티브 테스트까지 가능하다

**단점**
- 컨트롤러/DTO 코드가 먼저 있어야 테스트 작성이 가능하다
- Swagger 단독 사용 대비 초기 설정이 복잡하다
- 테스트 작성 비용이 있다

---

## 결정

**Spring REST Docs + restdocs-api-spec 조합을 채택한다.**

1. 운영 코드의 깔끔함을 최우선으로 두었다.
2. Swagger의 인터랙티브 테스트 기능은 OAS 변환을 통해 동일하게 확보할 수 있어 단점이 상쇄된다.  
3. 테스트 작성 강제화는 프로젝트 유지보수 및 신뢰성을 부여하는데 매우 중요한 작업이므로 비용만큼 이점을 얻을 수 있다

---

## 적용 흐름

```
컨트롤러 구현
      ↓
MockMvc 테스트 작성 + document() 스니펫 정의
      ↓
테스트 실행 → /build/generated-snippets/*.adoc 자동 생성
      ↓
src/docs/asciidoc/ 에서 스니펫 조합 → .adoc 작성
      ↓
restdocs-api-spec 플러그인으로 OAS(openapi.yaml) 변환
      ↓
Swagger UI로 OAS 렌더링 → 인터랙티브 테스트 가능
```

---

## 적용 시점

Spring REST Docs는 코드가 먼저 있어야 테스트 작성이 가능하다.  
따라서 아래 순서로 진행한다.

1. **현재** — `openapi.yaml` 초안 작성 (AI 코드 생성 가이드 용도)
2. **코드 구현 시작 후** — 컨트롤러 구현과 동시에 REST Docs 테스트 작성
3. **테스트 작성 완료 후** — OAS 변환 및 Swagger UI 연동

---

## 참고

- [컬리 기술 블로그 — Spring REST Docs 가이드](https://helloworld.kurly.com/blog/spring-rest-docs-guide/)
- [Spring REST Docs 공식 문서](https://spring.io/projects/spring-restdocs)
- [restdocs-api-spec GitHub](https://github.com/ePages-de/restdocs-api-spec)