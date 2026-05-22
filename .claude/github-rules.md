# GitHub 관리 규칙

## 이슈 유형 & 템플릿

| type       | 템플릿 파일             | 설명                       |
|------------|-------------------------|----------------------------|
| feat       | feat-template.md        | 새 기능 구현               |
| fix        | fix-template.md         | 버그 수정                  |
| chore      | chore-template.md       | 설정·의존성·코드 정리      |
| refactor   | refactor-template.md    | 기능 변경 없는 코드 개선   |
| docs       | docs-template.md        | 문서 작성·수정             |
| discussion | discussion-template.md  | 아키텍처·전략 논의         |

이슈 생성 시 `gh issue create` 에 `--template <템플릿 파일명>` 옵션을 사용한다.

---

## 브랜치명
`{type}/#{이슈번호}-{짧은-설명}` (CONTRIBUTING.md 기준)

예: `feat/#10-base-entity`, `chore/#8-add-dependencies`

## 커밋 메시지
- `Co-Authored-By` 절대 포함하지 않는다.
- 형식: `type: 작업 내용 (#이슈번호)`

## PR
- 제목: `[TYPE] 작업 내용 (#이슈번호)`
- 본문: PR 템플릿 (Issue ID / 작업 내용 / 참고사항)
- `close #이슈번호` 본문에 포함
- Milestone 반드시 연결

## GitHub CLI 참고
- Milestone 생성/조회: `gh api repos/{owner}/{repo}/milestones`
- Issue 생성/수정: `gh issue create`, `gh issue edit`
- PR 생성: `gh pr create`

---

## 커밋 전 체크리스트

- [ ] `./gradlew compileJava` 성공
- [ ] 민감 파일 미포함 (`*.env`, `application-local.yml` 등)
- [ ] `.gitignore`에 민감 파일 등록 여부 확인
- [ ] 이번 이슈와 무관한 파일 미포함
- [ ] **엔티티 포함 시**: `@Table(name=...)`, `@Column(name=...)`, `@JoinColumn(name=...)`을 `docs/schema/ddl.sql`과 직접 대조

## 커밋 단위 규칙

하나의 이슈를 하나의 커밋으로 올리지 않는다. 논리적 단위로 나누어 커밋한다.

예시:
- 엔티티 이슈: (1) 엔티티 클래스, (2) 리포지토리
- Security 이슈: (1) SecurityConfig, (2) JwtAuthenticationFilter
- 서비스 이슈: (1) 도메인 로직, (2) 서비스 레이어, (3) 컨트롤러+DTO
