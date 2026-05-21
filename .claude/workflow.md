# 작업 흐름 (12단계)

새 작업을 시작할 때 반드시 아래 순서를 따른다.

```
1.  GitHub Issue 확인 (gh issue view)
2.  main 브랜치 최신화 (git pull)
3.  이슈 브랜치 생성 (CONTRIBUTING.md 컨벤션)
4.  superpowers:writing-plans 스킬로 구현 계획 수립 → server/docs/superpowers/plans/ 에 저장
    → 사용자가 계획을 수용/거부/수정한 뒤 다음 단계로
5.  superpowers:subagent-driven-development 스킬로 Codex CLI에 구현 위임
    (codex exec "프롬프트" — .claude/codex-delegation.md 참고)
6.  빌드 및 테스트 검증 (./gradlew test) → 실패 시 Codex에 수정 재위임
7.  커밋 (.claude/github-rules.md 참고)
8.  push → PR 생성 (gh pr create)
9.  /codex:review 플러그인으로 Codex에게 코드 리뷰 위임
    → Claude가 결과를 수신하여 GitHub 인라인 코멘트로 포스팅 (gh api 사용)
10. Claude가 리뷰 판정 → 수용/거부 결정 (.claude/review-process.md 참고)
    - 수용: Codex에게 수정 구현 위임 → 빌드 검증 → 커밋 → push → PR 코멘트에 판정 결과 포스팅 → 9단계로 돌아가 재리뷰
    - 거부: 거부 사유를 PR 코멘트에 남기고 11단계로 이동
    (수용/거부 반복 — 아래 조건을 충족할 때까지 9~10단계를 반복)
11. 아래 두 조건 중 하나를 충족하면 사용자에게 알림
    - Codex가 "문제 없음 / Approve / Merge 가능" 판정을 내린 경우
    - Claude가 Codex의 모든 리뷰 항목을 거부하여 추가 수정이 없는 경우
12. PR merge는 사용자가 직접 진행
```
