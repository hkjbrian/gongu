#!/bin/bash
# 에이전트 파이프라인 디스패처 — 한 번 실행에 한 단계만 수행한다.
#
#   dispatch.sh                  정기 실행 (launchd). PIPELINE_ENABLED=true 일 때만 동작
#   dispatch.sh --dry-run        GitHub 상태를 읽어 다음에 실행할 단계만 출력
#   dispatch.sh --stage <stage> <번호>   특정 단계를 수동 실행 (propose 는 번호 대신 -)
#
# 설계: docs/superpowers/specs/2026-10-03-agent-pipeline-design.md
set -uo pipefail

PIPELINE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$PIPELINE_DIR/../.." && pwd)"

. "$PIPELINE_DIR/config.env"
# 로컬 오버라이드 (gitignore) — 예: PIPELINE_ENABLED=true
[ -f "$PIPELINE_DIR/config.local.env" ] && . "$PIPELINE_DIR/config.local.env"
. "$PIPELINE_DIR/lib.sh"

LOG_DIR="${LOG_DIR:-$PIPELINE_DIR/logs}"
WT_BASE="${WT_BASE:-$ROOT/.claude/worktrees}"
LOCK_DIR="$LOG_DIR/.lock"
LAST_PROPOSE_FILE="$LOG_DIR/last-propose"
STOP_FILE="${STOP_FILE:-$PIPELINE_DIR/STOP}"

# ---------- 다음 작업 선택 ----------

# propose_due → 에이전트 A 를 실행할 때면 0, 아니면 1, 조회 오류면 2
propose_due() {
  local backlog=0 label c
  for label in ai:proposed ai:ready ai:plan-review ai:plan-revise ai:plan-approved; do
    c=$(q_count "$label") || return 2
    backlog=$((backlog + ${c:-0}))
  done
  [ "$backlog" -lt "$BACKLOG_MIN" ] || return 1
  [ -f "$LAST_PROPOSE_FILE" ] || return 0
  local last now
  last=$(cat "$LAST_PROPOSE_FILE")
  now=$(date +%s)
  [ $((now - last)) -ge $((PROPOSE_INTERVAL_HOURS * 3600)) ]
}

# select_action → "<stage> <대상>" 한 줄. 할 일이 없으면 빈 출력 + return 0
# GitHub 조회가 하나라도 실패하면 아무것도 출력하지 않고 return 1 (조회 오류를 "할 일 없음"으로 오해하지 않는다)
select_action() {
  local n list rc
  # 락을 쥔 상태에서 ai:implementing 이 남아 있으면 이전 실행이 비정상 종료한 것
  list=$(q_list issue ai:implementing) || return 1
  n=${list%%$'\n'*}
  if [ -n "$n" ]; then echo "orphan $n"; return 0; fi

  list=$(q_list pr ai:reviewing) || return 1
  n=${list%%$'\n'*}
  if [ -n "$n" ]; then echo "review $n"; return 0; fi

  n=$(first_ready ai:plan-approved); rc=$?
  [ "$rc" = 2 ] && return 1
  if [ "$rc" = 0 ]; then echo "implement $n"; return 0; fi

  list=$(q_list issue ai:plan-revise) || return 1
  n=${list%%$'\n'*}
  if [ -n "$n" ]; then echo "replan $n"; return 0; fi

  n=$(first_ready ai:ready); rc=$?
  [ "$rc" = 2 ] && return 1
  if [ "$rc" = 0 ]; then echo "plan $n"; return 0; fi

  propose_due; rc=$?
  [ "$rc" = 2 ] && return 1
  if [ "$rc" = 0 ]; then echo "propose -"; fi
  return 0
}

# ---------- 워크트리 ----------

# prepare_worktree <stage> <대상> → 실행 디렉터리 경로 출력
prepare_worktree() {
  local stage=$1 target=$2 wt branch
  # git fetch 는 run_stage 가 fetch_origin 으로 먼저 수행한다 (네트워크 실패가 이슈를 blocked 시키지 않도록)
  case "$stage" in
    propose|plan|replan)
      # 읽기 전용 단계 — 공용 워크트리를 origin/main 으로 초기화해 재사용
      wt="$WT_BASE/ai-planner"
      if [ -d "$wt" ]; then
        git -C "$wt" checkout -q --detach -f origin/main && git -C "$wt" clean -fdq || return 1
      else
        git -C "$ROOT" worktree add -q --detach "$wt" origin/main || return 1
      fi
      ;;
    implement)
      wt="$WT_BASE/ai-$target"
      # 이전 시도의 잔여물은 버리고 새로 시작 (커밋은 브랜치에, 미푸시 변경은 blocked 시 사람이 확인)
      [ -d "$wt" ] && git -C "$ROOT" worktree remove --force "$wt"
      git -C "$ROOT" worktree add -q --detach "$wt" origin/main || return 1
      ;;
    review)
      branch=$(q_pr_branch "$target") || return 1
      # 사람이 쓰는 워크트리를 건드리지 않도록 항상 파이프라인 전용 detached 워크트리를 쓴다.
      # detached 이므로 푸시는 `git push origin HEAD:refs/heads/<브랜치>` 형식이어야 한다 (review.md 에서 안내)
      wt="$WT_BASE/ai-pr-$target"
      if [ -d "$wt" ]; then
        git -C "$wt" checkout -q --detach -f "origin/$branch" && git -C "$wt" clean -fdq || return 1
      else
        git -C "$ROOT" worktree add -q --detach "$wt" "origin/$branch" || return 1
      fi
      ;;
  esac
  echo "$wt"
}

# ---------- 단계 실행 ----------

stage_prompt() {
  local stage=$1 target=$2 file mode=""
  case "$stage" in
    replan) file=plan; mode="MODE=replan" ;;
    plan) file=plan; mode="MODE=plan" ;;
    *) file=$stage ;;
  esac
  cat <<EOF
너는 gongu 에이전트 파이프라인의 '$stage' 단계를 헤드리스로 수행한다. 사람에게 질문할 수 없다.
지시서 $PIPELINE_DIR/prompts/$file.md 를 Read 도구로 끝까지 읽고 그대로 수행하라.

대상: ${target/#-/(없음)}
파라미터: REPO=$REPO $mode PROPOSE_MAX=$PROPOSE_MAX REVIEW_ROUND=$(review_round_param "$stage" "$target")/$MAX_REVIEW_ROUNDS

응답의 마지막 줄은 반드시 지시서에 정의된 'PIPELINE_RESULT: ...' 한 줄이어야 한다.
EOF
}

review_round_param() {
  if [ "$1" = review ]; then echo "$REVIEW_ROUND"; else echo 0; fi
}

stage_model() {
  case "$1" in
    propose) echo "$MODEL_PROPOSE" ;; plan|replan) echo "$MODEL_PLAN" ;;
    implement) echo "$MODEL_IMPLEMENT" ;; review) echo "$MODEL_REVIEW" ;;
  esac
}

stage_timeout() {
  case "$1" in
    propose) echo "$TIMEOUT_PROPOSE" ;; plan|replan) echo "$TIMEOUT_PLAN" ;;
    implement) echo "$TIMEOUT_IMPLEMENT" ;; review) echo "$TIMEOUT_REVIEW" ;;
  esac
}

# run_claude <작업 디렉터리> <stage> <대상> <출력 파일> → claude 종료 코드
run_claude() {
  local wt=$1 stage=$2 target=$3 out=$4
  # 지시서의 bash 예시(-R $REPO)가 그대로 동작하도록 환경변수로도 넘기고, gh-api 래퍼를 PATH 에 올린다
  ( cd "$wt" && export REPO && export PATH="$PIPELINE_DIR/bin:$PATH" && gtimeout "$(stage_timeout "$stage")" claude -p "$(stage_prompt "$stage" "$target")" \
      --model "$(stage_model "$stage")" \
      --settings "$PIPELINE_DIR/claude-settings.json" \
      --permission-mode acceptEdits \
      --add-dir "$PIPELINE_DIR" \
      --output-format json ) > "$out" 2> "${out%.json}.err"
}

# lfail <stage> <대상> <동작> — GitHub 라벨/코멘트 변경 실패를 기록·알림한다 (호출부가 return 1)
# GitHub API 환경 문제로 보고 대상 이슈에 ai:blocked 를 붙이지 않는다
lfail() {
  log "라벨 전이 실패 $1 #$2: $3"
  m_notify "라벨 전이 실패 ($1 #$2: $3) — GitHub 연결 확인 필요"
}

# fail <stage> <대상> <사유> <로그 파일>
fail() {
  local stage=$1 target=$2 reason=$3 logfile=$4
  log "FAIL $stage $target: $reason"
  m_notify "$stage #$target 중단: $reason"
  [ "$target" = - ] && return 0
  # blocked 를 먼저 붙인다 (뒤 단계가 실패해도 중단 표시는 남도록). 여기서의 실패는 로그+알림만
  m_label_add "$target" ai:blocked || lfail "$stage" "$target" "ai:blocked 부착"
  if [ "$stage" = implement ]; then
    m_label_rm "$target" ai:implementing || lfail "$stage" "$target" "ai:implementing 제거"
  fi
  m_comment "$target" "🤖 파이프라인 \`$stage\` 단계가 중단되었습니다.

- 사유: $reason
- 로컬 로그: \`$logfile\`

원인을 해결한 뒤 \`ai:blocked\` 를 제거하고 재개할 상태 라벨을 붙여 주세요." || lfail "$stage" "$target" "중단 코멘트"
  return 0
}

# apply_result <stage> <대상> <결과> <종료 코드> <로그 파일>
# 라벨 전이·코멘트가 실패하면 lfail 후 return 1 (ai:blocked 는 붙이지 않는다)
apply_result() {
  local stage=$1 target=$2 result=$3 rc=$4 logfile=$5 n rounds from to bad

  if [ "$rc" = 124 ]; then fail "$stage" "$target" "타임아웃 ($(stage_timeout "$stage"))" "$logfile"; return 0; fi
  if [ -z "$result" ]; then fail "$stage" "$target" "결과 줄 없음 (exit $rc)" "$logfile"; return 0; fi

  case "$stage:$result" in
    *:blocked*)
      fail "$stage" "$target" "${result#blocked}" "$logfile" ;;

    propose:proposed\ *)
      bad=""
      for n in ${result#proposed }; do
        m_label_add "$n" ai:proposed || { lfail "$stage" "$n" "ai:proposed 부착"; bad=1; }
      done
      date +%s > "$LAST_PROPOSE_FILE"   # 라벨 실패여도 기록해 같은 제안을 반복 생성하지 않는다
      m_notify "새 제안 이슈: ${result#proposed } — 검토 후 ai:ready"
      [ -z "$bad" ] || return 1 ;;
    propose:none)
      date +%s > "$LAST_PROPOSE_FILE" ;;

    plan:planned|replan:planned)
      [ "$stage" = plan ] && from=ai:ready || from=ai:plan-revise
      to=ai:plan-review
      for n in $AUTO_APPROVE_PLAN_TYPES; do
        has_label "$target" "$n" && to=ai:plan-approved
      done
      transition "$target" "$from" "$to" || { lfail "$stage" "$target" "$from → $to"; return 1; }
      [ "$to" = ai:plan-review ] && m_notify "#$target 계획 검토 대기" ;;
    plan:invalid|replan:invalid)
      [ "$stage" = plan ] && from=ai:ready || from=ai:plan-revise
      transition "$target" "$from" ai:needs-human || { lfail "$stage" "$target" "$from → ai:needs-human"; return 1; }
      m_notify "#$target 에이전트가 이슈 타당성에 이의를 제기함" ;;

    implement:pr\ *)
      n=${result#pr }
      transition "$target" ai:implementing ai:in-pr || { lfail "$stage" "$target" "ai:implementing → ai:in-pr"; return 1; }
      m_label_add "$n" ai:reviewing || { lfail "$stage" "$n" "PR ai:reviewing 부착"; return 1; } ;;

    review:approved)
      transition "$target" ai:reviewing ai:merge-ready || { lfail "$stage" "$target" "ai:reviewing → ai:merge-ready"; return 1; }
      m_notify "PR #$target 머지 대기" ;;
    review:changes-pushed)
      rounds=$(q_review_rounds "$target") || { lfail "$stage" "$target" "리뷰 라운드 조회"; return 1; }
      if [ "${rounds:-0}" -ge "$MAX_REVIEW_ROUNDS" ]; then
        transition "$target" ai:reviewing ai:needs-human || { lfail "$stage" "$target" "ai:reviewing → ai:needs-human"; return 1; }
        m_comment "$target" "🤖 리뷰 라운드 상한(${MAX_REVIEW_ROUNDS})에 도달했습니다. 남은 지적 사항의 판단을 부탁드립니다." \
          || { lfail "$stage" "$target" "라운드 상한 코멘트"; return 1; }
        m_notify "PR #$target 리뷰 상한 도달"
      fi ;;

    *)
      fail "$stage" "$target" "알 수 없는 결과: $result" "$logfile" ;;
  esac
  return 0
}

# fetch_origin → git fetch. 실패하면 라벨은 건드리지 않고 연속 실패 횟수만 센다.
# FETCH_FAIL_STOP 회 이상 연속 실패하면 환경 오류로 보고 STOP 파일을 만든다
fetch_origin() {
  local f="$LOG_DIR/fetch-failures" c
  mkdir -p "$LOG_DIR"
  if git -C "$ROOT" fetch -q origin; then rm -f "$f"; return 0; fi
  c=$(cat "$f" 2>/dev/null)
  case "$c" in ''|*[!0-9]*) c=0 ;; esac
  c=$((c + 1))
  echo "$c" > "$f"
  log "git fetch 실패 ($c/${FETCH_FAIL_STOP:-3})"
  if [ "$c" -ge "${FETCH_FAIL_STOP:-3}" ]; then
    echo "git fetch 연속 ${c}회 실패" > "$STOP_FILE"
    m_notify "git fetch ${c}회 연속 실패로 파이프라인 정지 (해결 후 STOP 파일 삭제)"
  fi
  return 1
}

# run_stage <stage> <대상>
run_stage() {
  local stage=$1 target=$2 wt stamp out rc result rounds
  mkdir -p "$LOG_DIR/runs"
  stamp=$(date +%Y%m%d-%H%M%S)
  out="$LOG_DIR/runs/$stamp-$stage-${target/#-/x}.json"

  # 네트워크 실패는 이슈와 무관하므로 라벨 전이보다 먼저 확인한다 (실패 시 라벨 변경 없음)
  fetch_origin || return 1

  if [ "$stage" = review ]; then
    rounds=$(q_review_rounds "$target") || { lfail "$stage" "$target" "리뷰 라운드 조회 실패"; return 1; }
    REVIEW_ROUND=$((rounds + 1))
  fi

  if [ "$stage" = implement ]; then
    # 사전 전이가 실패하면 claude 를 돌리지 않는다 (상태 라벨 없이 구현이 진행되는 것을 막는다)
    transition "$target" ai:plan-approved ai:implementing || { lfail "$stage" "$target" "ai:plan-approved → ai:implementing"; return 1; }
  fi

  log "START $stage $target"
  if ! wt=$(prepare_worktree "$stage" "$target"); then
    fail "$stage" "$target" "워크트리 준비 실패" "$out"; return
  fi

  run_claude "$wt" "$stage" "$target" "$out"
  rc=$?

  local env_error
  if env_error=$(env_error_of "$out" "$rc"); then
    log "ENV ERROR $stage $target: $env_error — STOP 생성"
    echo "$(date '+%F %T') $stage $target rc=$rc ENV_ERROR $env_error" >> "$LOG_DIR/runs.log"
    if [ "$stage" = implement ]; then
      transition "$target" ai:implementing ai:plan-approved || lfail "$stage" "$target" "ai:plan-approved 복구"
    fi
    echo "$env_error" > "$STOP_FILE"
    m_notify "환경 오류로 파이프라인 정지: $env_error (해결 후 STOP 파일 삭제)"
    return
  fi
  result=$(parse_result "$out")
  log "END $stage $target rc=$rc result=${result:-<none>}"
  echo "$(date '+%F %T') $stage $target rc=$rc ${result:-<none>}" >> "$LOG_DIR/runs.log"

  apply_result "$stage" "$target" "$result" "$rc" "$out"
}

# ---------- 락 ----------

acquire_lock() {
  mkdir -p "$LOG_DIR"
  if mkdir "$LOCK_DIR" 2>/dev/null; then echo $$ > "$LOCK_DIR/pid"; return 0; fi
  local age=$(( $(date +%s) - $(stat -f %m "$LOCK_DIR" 2>/dev/null || date +%s) ))
  if [ "$age" -gt "$LOCK_STALE_SECONDS" ]; then
    log "stale lock (${age}s) 해제"
    rm -rf "$LOCK_DIR" && mkdir "$LOCK_DIR" && echo $$ > "$LOCK_DIR/pid" && return 0
  fi
  return 1
}

release_lock() { rm -rf "$LOCK_DIR"; }

# ---------- 진입점 ----------

main() {
  local mode=tick stage target action

  case "${1:-}" in
    --dry-run) mode=dry; DRY_RUN=1 ;;
    --stage)
      mode=manual; stage=${2:?stage 필요}; target=${3:?대상 번호 필요 (propose 는 -)}
      case "$stage" in propose|plan|replan|implement|review) ;; *) echo "알 수 없는 stage: $stage" >&2; exit 2 ;; esac ;;
    "") ;;
    *) echo "usage: $0 [--dry-run | --stage <stage> <번호>]" >&2; exit 2 ;;
  esac

  if [ "$mode" = tick ] && [ "$PIPELINE_ENABLED" != true ]; then exit 0; fi
  if [ -f "$STOP_FILE" ] && [ "$mode" != dry ]; then log "STOP 파일 존재 — 종료"; exit 0; fi

  if [ "$mode" = dry ]; then
    if ! action=$(select_action); then
      log "GitHub 조회 실패 — 다음 tick 에 재시도"; m_notify "GitHub 조회 실패 — 다음 tick 에 재시도"; exit 0
    fi
    log "다음 작업: ${action:-없음}"
    exit 0
  fi

  if ! acquire_lock; then log "다른 실행이 진행 중 — 종료"; exit 0; fi
  trap release_lock EXIT

  if [ "$mode" = manual ]; then
    run_stage "$stage" "$target"
    return
  fi

  if ! action=$(select_action); then
    log "GitHub 조회 실패 — 다음 tick 에 재시도"; m_notify "GitHub 조회 실패 — 다음 tick 에 재시도"; exit 0
  fi
  [ -z "$action" ] && exit 0
  stage=${action%% *}; target=${action#* }
  if [ "$stage" = orphan ]; then
    fail implement "$target" "이전 구현 실행이 비정상 종료됨 (ai:implementing 잔존)" "$LOG_DIR/runs.log"
    return
  fi
  run_stage "$stage" "$target"
}

# 테스트에서 source 할 때는 main 을 실행하지 않는다
if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
