#!/bin/bash
# AI 파이프라인 라벨 생성 — 멱등 연산

set -euo pipefail

PIPELINE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$PIPELINE_DIR/config.env"

# 사람 대기 — FBCA04 (노란색)
gh label create ai:proposed -R "$REPO" --color FBCA04 --description "에이전트가 제안, 사람 검토 대기" --force
gh label create ai:plan-review -R "$REPO" --color FBCA04 --description "계획 게시됨, 사람 검토 대기" --force
gh label create ai:merge-ready -R "$REPO" --color FBCA04 --description "사람 머지 대기" --force
gh label create ai:needs-human -R "$REPO" --color FBCA04 --description "에이전트가 판단 불가, 반박" --force

# 사람이 붙이는 승인 — 0E8A16 (초록색)
gh label create ai:ready -R "$REPO" --color 0E8A16 --description "에이전트가 계획을 세워도 됨" --force
gh label create ai:plan-approved -R "$REPO" --color 0E8A16 --description "구현 착수 가능" --force
gh label create ai:plan-revise -R "$REPO" --color 0E8A16 --description "사람이 피드백 남김, 재계획 요청" --force

# 진행 중 — 1D76DB (파란색)
gh label create ai:implementing -R "$REPO" --color 1D76DB --description "구현 진행 중 (실행 중 표식)" --force
gh label create ai:in-pr -R "$REPO" --color 1D76DB --description "PR 생성됨" --force
gh label create ai:reviewing -R "$REPO" --color 1D76DB --description "리뷰 루프 진행 중" --force

# 중단 — B60205 (빨간색)
gh label create ai:blocked -R "$REPO" --color B60205 --description "실패·타임아웃, 사람 조치 필요" --force

# 기타
gh label create ai:paused -R "$REPO" --color C5DEF5 --description "파이프라인이 건드리지 않음" --force
gh label create ai:rejected -R "$REPO" --color EEEEEE --description "사람이 기각" --force
gh label create ai:scope-change -R "$REPO" --color D93F0B --description "Non-Goals, 기존 ADR과 충돌" --force
