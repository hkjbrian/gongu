# Codex PR Review Setup

## 현재 ruleset 스냅샷

2026-04-22 기준 GitHub 저장소 `hkjbrian/gongu`의 active ruleset(`main`)은 아래와 같다.

- 대상 브랜치: `refs/heads/main`
- 강제 적용: `active`
- 삭제 금지
- 강제 push 금지
- PR 통해서만 머지
- required approving review count: `0`
- dismiss stale reviews on push: `false`
- require code owner review: `false`
- require last push approval: `false`
- require review thread resolution: `false`
- allowed merge methods: `merge`, `squash`, `rebase`

즉, 현재 ruleset에는 아래 항목이 아직 없다.

- required status checks
- required conversation resolution
- required approving reviews

## Codex를 merge gate로 쓰려면 추가로 필요한 것

- GitHub Actions 활성화
- 저장소 Secret `OPENAI_API_KEY`
- 선택 사항: Repository Variable `OPENAI_MODEL`
- ruleset에 `Codex PR Review / codex-review` 체크를 required status check로 추가
- 필요하면 ruleset에 review thread resolution 요구 조건 추가

## 현재 ruleset 그대로 시작하는 경우

현재 ruleset만 유지해도 아래는 가능하다.

- PR 자동 리뷰 실행
- PR summary comment 작성
- 라인별 review comment 작성
- 새 커밋 기준 re-review
- 해결된 thread resolve 시도
- 필요 시 bot approve

다만 이 경우 Codex 리뷰 결과가 merge를 막는 공식 gate는 아니다.

## Codex를 merge gate로 쓰는 경우

아래 두 가지를 ruleset에 추가하는 구성을 권장한다.

1. `Codex PR Review / codex-review`를 required status check로 등록
2. review thread resolution을 required로 켜기

## 추천 운영 방식

1. 첫 단계에서는 `.github/codex-review.json`의 기본값으로 시작한다.
2. 실제 PR 2~3개를 돌려보고 제목/라벨 필터와 severity 기준을 조정한다.
3. 인라인 코멘트 품질이 안정화되면 `auto_approve_when_clean`을 `true`로 바꾼다.
4. 그 뒤에만 approve를 merge gate로 사용할지 별도 검증한다.

## 선별 규칙 예시

- 특정 제목 패턴만 리뷰: `title_include`
- 특정 라벨이 있으면 강제 리뷰: `force_labels`
- 특정 라벨이 있으면 스킵: `ignore_labels`
- 드래프트 PR 제외: `skip_drafts`

## 스레드 자동 정리 방식

- Codex는 각 인라인 코멘트에 fingerprint를 숨김 마커로 남긴다.
- 새 커밋이 오면 같은 fingerprint가 다시 검출되는지 비교한다.
- 사라진 fingerprint는 해당 스레드에 해결 커밋을 답글로 남기고 resolve 한다.
- 다시 나타난 fingerprint는 기존 스레드를 reopen 대상으로 본다.

## 주의 사항

- 이 워크플로는 `pull_request_target`을 사용하므로 PR의 untrusted code를 실행하지 않는다.
- 리뷰를 위해 필요한 변경 파일 내용은 GitHub API로 읽고, base branch의 스크립트만 실행한다.
- 현재 ruleset은 required approving review count가 `0`이므로, approve는 merge gate가 아니라 보조 신호에 가깝다.
- 자동 approve를 merge 조건으로 의미 있게 쓰려면 ruleset에서 approving review 요구 조건을 별도로 설계해야 한다.
