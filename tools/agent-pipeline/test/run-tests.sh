#!/bin/bash
# 에이전트 파이프라인 단위 테스트 — 순수 bash (macOS bash 3.2 호환), 외부 호출 없음.
#   bash tools/agent-pipeline/test/run-tests.sh
# 각 테스트는 서브셸에서 dispatch.sh 를 source 하고 GitHub 조회/변경 함수를 가짜로 교체한다.

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PIPELINE_DIR="$(cd "$TEST_DIR/.." && pwd)"
RESULTS=$(mktemp)
trap 'rm -f "$RESULTS"' EXIT

# ---------- assert 헬퍼 (서브셸에서도 결과 파일로 집계) ----------

CURRENT_TEST=""
assert_eq() { # <설명> <기대> <실제>
  if [ "$2" = "$3" ]; then echo "P" >> "$RESULTS"
  else
    echo "F" >> "$RESULTS"
    printf 'FAIL [%s] %s\n  expected: %s\n  actual:   %s\n' "$CURRENT_TEST" "$1" "$2" "$3"
  fi
}
assert_contains() { # <설명> <전체> <부분문자열>
  case "$2" in
    *"$3"*) echo "P" >> "$RESULTS" ;;
    *) echo "F" >> "$RESULTS"; printf 'FAIL [%s] %s\n  missing:  %s\n  in:       %s\n' "$CURRENT_TEST" "$1" "$3" "$2" ;;
  esac
}
assert_rc() { # <설명> <기대 rc> <실제 rc>
  assert_eq "$1 (rc)" "$2" "$3"
}

# ---------- 가짜 환경 ----------
# 조회 데이터: 변수로 지정 (키는 영숫자 외 문자를 _ 로 치환)
#   L_<issue|pr>_<label>="번호 번호"   q_list     C_<label>=N  q_count
#   B_<n>=본문   S_<n>=OPEN|CLOSED(기본 OPEN)   LB_<n>="라벨 라벨"   RR=N   BR_<n>=브랜치
# 실패 주입: QF_list_<issue|pr>_<label>=1  QF_count_<label>=1  QF_body_<n>=1  QF_state_<n>=1  (조회가 non-zero)
#            ADD_RC / RM_RC / COMMENT_RC (m_* 의 반환값, 기본 0)   GIT_RC (git 가짜의 반환값, fetch 실패 재현)
_k() { echo "${1//[^a-zA-Z0-9]/_}"; }

setup() {
  LOG_DIR=$(mktemp -d)
  export LOG_DIR
  . "$PIPELINE_DIR/dispatch.sh"
  set +u
  CALLS="$LOG_DIR/calls"; : > "$CALLS"
  RR=0

  q_list()  { local f="QF_list_$1_$(_k "$2")"; [ -n "${!f}" ] && return 1
              local v="L_$1_$(_k "$2")"; local x="${!v}"; [ -n "$x" ] && printf '%s\n' $x; return 0; }
  q_count() { local f="QF_count_$(_k "$1")"; [ -n "${!f}" ] && return 1
              local v="C_$(_k "$1")"; echo "${!v}"; }
  q_body()  { local f="QF_body_$1"; [ -n "${!f}" ] && return 1
              local v="B_$1"; printf '%s\n' "${!v}"; }
  q_state() { local f="QF_state_$1"; [ -n "${!f}" ] && return 1
              local v="S_$1"; echo "${!v:-OPEN}"; }
  q_labels(){ local v="LB_$1"; printf '%s\n' ${!v}; }
  q_review_rounds() { echo "$RR"; }
  q_pr_branch() { local v="BR_$1"; echo "${!v}"; }

  ADD_RC=0; RM_RC=0; COMMENT_RC=0; GIT_RC=0
  m_label_add() { echo "add $1 $2" >> "$CALLS"; return "$ADD_RC"; }
  m_label_rm()  { echo "rm $1 $2" >> "$CALLS"; return "$RM_RC"; }
  m_comment()   { echo "comment $1 $(printf '%s' "$2" | tr '\n' ' ')" >> "$CALLS"; return "$COMMENT_RC"; }
  git() { return "$GIT_RC"; }   # fetch_origin 의 git fetch 대역 (다른 git 호출은 prepare_worktree 가짜가 대신한다)
  m_notify()    { echo "notify $1" >> "$CALLS"; }
  log() { :; }
}

calls() { cat "$CALLS"; }
label_calls() { grep -E '^(add|rm|comment) ' "$CALLS"; }
nl=$'\n'

run_test() {
  CURRENT_TEST=$1
  ( "$1" )
}

# ---------- 1. 우선순위 ----------
t_priority() {
  setup
  L_issue_ai_implementing="9"; L_pr_ai_reviewing="30"; L_issue_ai_plan_approved="20"
  L_issue_ai_plan_revise="15"; L_issue_ai_ready="12"
  assert_eq "orphan 최우선" "orphan 9" "$(select_action)"
  L_issue_ai_implementing=""
  assert_eq "review" "review 30" "$(select_action)"
  L_pr_ai_reviewing=""
  assert_eq "implement" "implement 20" "$(select_action)"
  L_issue_ai_plan_approved=""
  assert_eq "replan" "replan 15" "$(select_action)"
  L_issue_ai_plan_revise=""
  assert_eq "plan" "plan 12" "$(select_action)"
  L_issue_ai_ready=""
  assert_eq "propose" "propose -" "$(select_action)"
  C_ai_ready=5
  assert_eq "할 일 없음" "" "$(select_action)"
}

# ---------- 2. 선행 이슈 ----------
t_deps() {
  setup
  B_20="설명${nl}선행: #10, #11${nl}기타 #99"
  assert_eq "deps_of 추출" "10${nl}11" "$(deps_of 20)"
  B_21="- **선행**: #5"
  assert_eq "마크다운 변형" "5" "$(deps_of 21)"
  B_22="**선행:** #7 #8"
  assert_eq "마크다운 변형 2" "7${nl}8" "$(deps_of 22)"
  B_23="본문에 선행 이슈 #3 이 있다"
  assert_eq "선행: 줄 아닌 것은 무시" "" "$(deps_of 23)"

  S_10=CLOSED; S_11=OPEN
  deps_closed 20; assert_rc "하나라도 OPEN 이면 false" 1 $?
  S_11=CLOSED
  deps_closed 20; assert_rc "모두 CLOSED 면 true" 0 $?
  deps_closed 23; assert_rc "선행 없음이면 true" 0 $?
  S_11=OPEN
  L_issue_ai_ready="20 24"
  assert_eq "OPEN 선행 건너뛰고 다음 후보" "plan 24" "$(select_action)"
  L_issue_ai_ready="20"
  C_ai_ready=5
  assert_eq "후보 전부 막히면 없음" "" "$(select_action)"
  S_11=CLOSED
  assert_eq "선행 해소 시 선택" "plan 20" "$(select_action)"
}

# ---------- 3. propose_due ----------
t_propose_due() {
  setup
  C_ai_proposed=2; C_ai_ready=1; C_ai_plan_review=1; C_ai_plan_revise=0; C_ai_plan_approved=1
  propose_due; assert_rc "백로그 합계 5 >= BACKLOG_MIN" 1 $?
  C_ai_plan_approved=0   # 합계 4
  rm -f "$LAST_PROPOSE_FILE"
  propose_due; assert_rc "미만 + 파일 없음" 0 $?
  date +%s > "$LAST_PROPOSE_FILE"
  propose_due; assert_rc "미만 + 최근 제안" 1 $?
  echo $(( $(date +%s) - PROPOSE_INTERVAL_HOURS * 3600 - 60 )) > "$LAST_PROPOSE_FILE"
  propose_due; assert_rc "미만 + 오래됨" 0 $?
  echo $(( $(date +%s) - 3600 )) > "$LAST_PROPOSE_FILE"
  propose_due; assert_rc "1시간 전 제안" 1 $?
}

# ---------- 4. apply_result 전이 ----------
t_apply_plan() {
  setup; AUTO_APPROVE_PLAN_TYPES=""
  apply_result plan 30 planned 0 /dev/null >/dev/null
  assert_eq "plan planned" "add 30 ai:plan-review${nl}rm 30 ai:ready${nl}notify #30 계획 검토 대기" "$(calls)"
  : > "$CALLS"
  apply_result replan 31 planned 0 /dev/null >/dev/null
  assert_eq "replan planned" "add 31 ai:plan-review${nl}rm 31 ai:plan-revise${nl}notify #31 계획 검토 대기" "$(calls)"
  : > "$CALLS"
  apply_result plan 32 invalid 0 /dev/null >/dev/null
  assert_contains "plan invalid -> needs-human" "$(calls)" "add 32 ai:needs-human"
  assert_contains "plan invalid rm ready" "$(calls)" "rm 32 ai:ready"
  : > "$CALLS"
  apply_result replan 33 invalid 0 /dev/null >/dev/null
  assert_contains "replan invalid rm revise" "$(calls)" "rm 33 ai:plan-revise"
  assert_contains "replan invalid needs-human" "$(calls)" "add 33 ai:needs-human"
}

t_apply_plan_auto() {
  setup; AUTO_APPROVE_PLAN_TYPES="docs"
  LB_40="docs ai:ready"
  apply_result plan 40 planned 0 /dev/null >/dev/null
  assert_eq "docs 라벨 자동 승인" "add 40 ai:plan-approved${nl}rm 40 ai:ready" "$(calls)"
  : > "$CALLS"
  LB_41="feature ai:ready"
  apply_result plan 41 planned 0 /dev/null >/dev/null
  assert_contains "docs 아니면 plan-review" "$(calls)" "add 41 ai:plan-review"
}

t_apply_implement_review() {
  setup
  apply_result implement 7 "pr 250" 0 /dev/null >/dev/null
  assert_eq "implement pr" "add 250 ai:reviewing${nl}add 7 ai:in-pr${nl}rm 7 ai:implementing" "$(calls)"
  : > "$CALLS"
  ADD_RC=1
  apply_result implement 8 "pr 251" 0 /dev/null >/dev/null; assert_rc "PR 라벨 추가 실패 -> 1" 1 $?
  assert_eq "PR 라벨 실패 시 이슈 라벨 호출 없음" "" "$(grep -E '^(add|rm) 8 ' "$CALLS")"
  : > "$CALLS"
  ADD_RC=0
  apply_result implement 9 "pr not-a-number" 0 /dev/null >/dev/null
  assert_contains "숫자가 아닌 PR 번호는 blocked" "$(calls)" "add 9 ai:blocked"
  : > "$CALLS"
  apply_result review 250 approved 0 /dev/null >/dev/null
  assert_contains "review approved" "$(calls)" "add 250 ai:merge-ready"
  assert_contains "review approved rm reviewing" "$(calls)" "rm 250 ai:reviewing"
  : > "$CALLS"
  RR=1; MAX_REVIEW_ROUNDS=3
  apply_result review 250 changes-pushed 0 /dev/null >/dev/null
  assert_eq "라운드 < MAX: 라벨 변화 없음" "" "$(label_calls)"
  RR=3
  apply_result review 250 changes-pushed 0 /dev/null >/dev/null
  assert_contains "라운드 >= MAX: needs-human" "$(calls)" "add 250 ai:needs-human"
  assert_contains "라운드 >= MAX: 코멘트" "$(calls)" "comment 250 "
  assert_contains "라운드 >= MAX: 코멘트 내용" "$(calls)" "리뷰 라운드 상한(3)"
}

t_apply_propose() {
  setup
  rm -f "$LAST_PROPOSE_FILE"
  apply_result propose - "proposed 300 301" 0 /dev/null >/dev/null
  assert_contains "300" "$(calls)" "add 300 ai:proposed"
  assert_contains "301" "$(calls)" "add 301 ai:proposed"
  [ -f "$LAST_PROPOSE_FILE" ]; assert_rc "proposed: last-propose 생성" 0 $?
  rm -f "$LAST_PROPOSE_FILE"; : > "$CALLS"
  apply_result propose - none 0 /dev/null >/dev/null
  [ -f "$LAST_PROPOSE_FILE" ]; assert_rc "none: last-propose 생성" 0 $?
  assert_eq "none: 라벨 변화 없음" "" "$(label_calls)"
}

# ---------- 5. 실패 ----------
t_failures() {
  setup
  apply_result plan 50 "" 124 /tmp/x.json >/dev/null
  assert_contains "124 blocked" "$(calls)" "add 50 ai:blocked"
  assert_contains "124 사유" "$(calls)" "타임아웃"
  : > "$CALLS"
  apply_result plan 51 "" 1 /tmp/x.json >/dev/null
  assert_contains "빈 결과 blocked" "$(calls)" "add 51 ai:blocked"
  assert_contains "빈 결과 사유" "$(calls)" "결과 줄 없음"
  : > "$CALLS"
  apply_result plan 52 "blocked 테스트 실패" 0 /tmp/x.json >/dev/null
  assert_contains "blocked 결과" "$(calls)" "add 52 ai:blocked"
  assert_contains "blocked 사유 전달" "$(calls)" "테스트 실패"
  : > "$CALLS"
  apply_result plan 53 "whatever" 0 /tmp/x.json >/dev/null
  assert_contains "알 수 없는 결과 blocked" "$(calls)" "add 53 ai:blocked"
  assert_contains "알 수 없는 결과 사유" "$(calls)" "알 수 없는 결과: whatever"
  : > "$CALLS"
  apply_result plan 54 "approved" 0 /tmp/x.json >/dev/null
  assert_contains "stage 와 안 맞는 결과도 blocked" "$(calls)" "add 54 ai:blocked"
  : > "$CALLS"
  apply_result implement 55 "" 124 /tmp/x.json >/dev/null
  assert_contains "implement 실패: implementing 제거" "$(calls)" "rm 55 ai:implementing"
  assert_contains "implement 실패: blocked" "$(calls)" "add 55 ai:blocked"
  : > "$CALLS"
  apply_result plan 56 "" 1 /tmp/x.json >/dev/null
  assert_eq "plan 실패에는 implementing 제거 없음" "" "$(grep 'ai:implementing' "$CALLS")"
  : > "$CALLS"
  apply_result propose - "" 124 /tmp/x.json >/dev/null
  assert_eq "propose 실패: 라벨·코멘트 없음" "" "$(label_calls)"
  assert_contains "propose 실패: 알림은 있음" "$(calls)" "notify propose #- 중단"
  : > "$CALLS"
  apply_result propose - "blocked x" 0 /tmp/x.json >/dev/null
  assert_eq "propose blocked: 라벨·코멘트 없음" "" "$(label_calls)"
}

# ---------- 6. parse_result ----------
t_parse_result() {
  setup
  local f="$LOG_DIR/o.json"
  jq -n --arg r "작업을 마쳤습니다.${nl}요약 줄${nl}${nl}PIPELINE_RESULT: pr 12" '{result:$r, is_error:false}' > "$f"
  assert_eq "여러 줄 결과에서 추출" "pr 12" "$(parse_result "$f")"
  jq -n --arg r 'text
`PIPELINE_RESULT: planned`' '{result:$r}' > "$f"
  assert_eq "백틱 감싼 결과" "planned" "$(parse_result "$f")"
  jq -n --arg r 'PIPELINE_RESULT: planned
중간
PIPELINE_RESULT: blocked 이유' '{result:$r}' > "$f"
  assert_eq "여러 개면 마지막" "blocked 이유" "$(parse_result "$f")"
  jq -n --arg r '결과 줄이 없음' '{result:$r}' > "$f"
  assert_eq "없으면 빈 문자열" "" "$(parse_result "$f")"
  echo '{not json' > "$f"
  assert_eq "깨진 파일" "" "$(parse_result "$f")"
  assert_eq "없는 파일" "" "$(parse_result "$LOG_DIR/nonexistent.json")"
  echo '{"subtype":"error_max_turns"}' > "$f"
  assert_eq "result 필드 없음" "" "$(parse_result "$f")"
}

# ---------- 7. 락 ----------
t_lock() {
  setup
  acquire_lock; assert_rc "첫 획득" 0 $?
  acquire_lock; assert_rc "두 번째 획득 실패" 1 $?
  release_lock
  [ ! -d "$LOCK_DIR" ]; assert_rc "release 후 락 없음" 0 $?
  acquire_lock; assert_rc "release 후 재획득" 0 $?
  touch -t 202001010000 "$LOCK_DIR"
  LOCK_STALE_SECONDS=0
  acquire_lock; assert_rc "오래된 락 회수" 0 $?
  assert_eq "회수 후 pid 기록" "$$" "$(cat "$LOCK_DIR/pid" 2>/dev/null)"
}

# ---------- run_stage 통합 ----------
fake_claude_env() { # <json 파일에 쓸 result 텍스트> <rc>
  FAKE_TEXT=$1; FAKE_RC=$2
  prepare_worktree() { mktemp -d; }
  run_claude() {
    if [ -n "$FAKE_TEXT" ]; then jq -n --arg r "$FAKE_TEXT" '{result:$r}' > "$4"; else echo '{}' > "$4"; fi
    return "$FAKE_RC"
  }
}

t_run_stage_plan() {
  setup; AUTO_APPROVE_PLAN_TYPES=""
  fake_claude_env "계획 작성 완료${nl}PIPELINE_RESULT: planned" 0
  run_stage plan 60 >/dev/null
  assert_eq "run_stage plan" "add 60 ai:plan-review${nl}rm 60 ai:ready${nl}notify #60 계획 검토 대기" "$(calls)"
  assert_contains "runs.log 기록" "$(cat "$LOG_DIR/runs.log")" "plan 60 rc=0 planned"
}

t_run_stage_implement() {
  setup
  fake_claude_env "PIPELINE_RESULT: pr 250" 0
  run_stage implement 7 >/dev/null
  assert_eq "run_stage implement" \
    "add 7 ai:implementing${nl}rm 7 ai:plan-approved${nl}add 250 ai:reviewing${nl}add 7 ai:in-pr${nl}rm 7 ai:implementing" "$(calls)"
}

t_run_stage_failures() {
  setup
  fake_claude_env "" 124
  run_stage implement 8 >/dev/null
  assert_contains "implement 타임아웃: blocked" "$(calls)" "add 8 ai:blocked"
  assert_contains "implement 타임아웃: implementing 제거" "$(calls)" "rm 8 ai:implementing"
  : > "$CALLS"
  prepare_worktree() { return 1; }
  run_stage plan 9 >/dev/null
  assert_contains "워크트리 실패" "$(calls)" "add 9 ai:blocked"
  assert_contains "워크트리 실패 사유" "$(calls)" "워크트리 준비 실패"
  : > "$CALLS"
  fake_claude_env "PIPELINE_RESULT: none" 0
  run_stage propose - >/dev/null
  assert_eq "propose none: 라벨 없음" "" "$(label_calls)"
  [ -f "$LAST_PROPOSE_FILE" ]; assert_rc "propose none: last-propose" 0 $?
}

t_run_stage_env_error() {
  setup; STOP_FILE="$LOG_DIR/STOP"
  prepare_worktree() { mktemp -d; }
  run_claude() { echo '{"type":"result","is_error":true,"terminal_reason":"api_error","result":"Failed to authenticate: OAuth session expired"}' > "$4"; return 1; }
  run_stage plan 61 >/dev/null
  assert_eq "인증 만료: 라벨·코멘트 없음" "" "$(label_calls)"
  assert_contains "인증 만료: STOP 생성" "$(cat "$STOP_FILE" 2>/dev/null)" "OAuth session expired"
  assert_contains "인증 만료: 알림" "$(calls)" "notify 환경 오류"
  : > "$CALLS"; rm -f "$STOP_FILE"
  run_stage implement 62 >/dev/null
  assert_eq "implement 환경 오류: plan-approved 로 복귀" \
    "add 62 ai:implementing${nl}rm 62 ai:plan-approved${nl}add 62 ai:plan-approved${nl}rm 62 ai:implementing" "$(label_calls)"
  : > "$CALLS"; rm -f "$STOP_FILE"
  run_claude() { : > "$4"; return 127; }
  run_stage plan 63 >/dev/null
  assert_eq "CLI 실행 실패: 라벨 없음" "" "$(label_calls)"
  [ -f "$STOP_FILE" ]; assert_rc "CLI 실행 실패: STOP" 0 $?
  : > "$CALLS"; rm -f "$STOP_FILE"
  run_claude() { echo '{"type":"result","is_error":false,"result":"작업 중 혼란"}' > "$4"; return 0; }
  run_stage plan 64 >/dev/null
  assert_contains "일반 실패는 기존대로 blocked" "$(calls)" "add 64 ai:blocked"
  [ ! -f "$STOP_FILE" ]; assert_rc "일반 실패는 STOP 없음" 0 $?
}

# ---------- 9. 실패 전파 (수정 1~3) ----------
t_transition_failures() {
  setup
  ADD_RC=1
  transition 70 ai:a ai:b; assert_rc "add 실패 -> 1" 1 $?
  assert_eq "add 실패 시 rm 미호출" "add 70 ai:b" "$(calls)"
  : > "$CALLS"; ADD_RC=0; RM_RC=1
  transition 71 ai:a ai:b; assert_rc "rm 실패 -> 1" 1 $?
  assert_eq "add 먼저, rm 나중" "add 71 ai:b${nl}rm 71 ai:a" "$(calls)"
  : > "$CALLS"; RM_RC=0
  transition 72 ai:a ai:b; assert_rc "모두 성공 -> 0" 0 $?
}

# m_label_rm 은 gh 를 직접 부르므로 gh 를 가짜 함수로 교체해 404/500 판정을 확인한다
t_label_rm_http() {
  setup
  unset -f m_label_rm; . "$PIPELINE_DIR/lib.sh"
  REPO=o/r; DRY_RUN=0
  gh() { echo "gh: Not Found (HTTP 404)"; return 1; }
  m_label_rm 80 ai:x; assert_rc "404(이미 없음) 는 성공" 0 $?
  gh() { echo "gh: Internal Server Error (HTTP 500)"; return 1; }
  m_label_rm 80 ai:x; assert_rc "500 은 실패" 1 $?
  gh() { echo "gh: could not resolve host"; return 1; }
  m_label_rm 80 ai:x; assert_rc "네트워크 오류는 실패" 1 $?
  gh() { return 0; }
  m_label_rm 80 ai:x; assert_rc "삭제 성공" 0 $?
}

t_q_review_rounds_failure() {
  setup
  unset -f q_review_rounds; . "$PIPELINE_DIR/lib.sh"
  REPO=o/r
  gh() { printf '%s\n' "$*" > "$LOG_DIR/gh-args"; echo 1; echo 2; return 0; }
  assert_eq "페이지별 합산" "3" "$(q_review_rounds 5)"
  assert_contains "OWNER 코멘트만 집계" "$(cat "$LOG_DIR/gh-args")" '.author_association == "OWNER"'
  gh() { echo 1; return 1; }
  q_review_rounds 5 >/dev/null; assert_rc "gh 실패가 awk 에 가려지지 않음" 1 $?
}

t_apply_transition_failure() {
  setup; AUTO_APPROVE_PLAN_TYPES=""
  ADD_RC=1
  apply_result plan 90 planned 0 /dev/null >/dev/null; assert_rc "plan 전이 실패 -> 1" 1 $?
  assert_eq "blocked 미부착" "" "$(grep 'ai:blocked' "$CALLS")"
  assert_contains "알림 기록" "$(calls)" "notify 라벨 전이 실패"
  assert_eq "코멘트 없음" "" "$(grep '^comment ' "$CALLS")"
  : > "$CALLS"
  apply_result implement 91 "pr 300" 0 /dev/null >/dev/null; assert_rc "implement 전이 실패 -> 1" 1 $?
  assert_eq "implement: blocked 미부착" "" "$(grep 'ai:blocked' "$CALLS")"
  : > "$CALLS"; ADD_RC=0; RM_RC=1
  apply_result review 92 approved 0 /dev/null >/dev/null; assert_rc "review rm 실패 -> 1" 1 $?
  assert_eq "review: blocked 미부착" "" "$(grep 'ai:blocked' "$CALLS")"
  assert_eq "머지 대기 알림 없음" "" "$(grep 'notify PR' "$CALLS")"
  : > "$CALLS"; RM_RC=0; COMMENT_RC=1; RR=3; MAX_REVIEW_ROUNDS=3
  apply_result review 93 changes-pushed 0 /dev/null >/dev/null; assert_rc "라운드 상한 코멘트 실패 -> 1" 1 $?
  assert_eq "상한: blocked 미부착" "" "$(grep 'ai:blocked' "$CALLS")"
  : > "$CALLS"; COMMENT_RC=0; ADD_RC=1
  apply_result propose - "proposed 300 301" 0 /dev/null >/dev/null; assert_rc "propose 라벨 실패 -> 1" 1 $?
  assert_contains "propose: 두 번째도 시도" "$(calls)" "add 301 ai:proposed"
  assert_eq "propose: blocked 없음" "" "$(grep 'ai:blocked' "$CALLS")"
  : > "$CALLS"; ADD_RC=0
  q_review_rounds() { return 1; }
  apply_result review 94 changes-pushed 0 /dev/null >/dev/null; assert_rc "라운드 조회 실패 -> 1" 1 $?
}

t_fail_label_errors() {
  setup
  ADD_RC=1; RM_RC=1; COMMENT_RC=1
  apply_result implement 95 "" 124 /tmp/x.json >/dev/null; assert_rc "fail 은 라벨 실패에도 0" 0 $?
  assert_contains "fail 내 실패는 알림만" "$(calls)" "notify 라벨 전이 실패"
}

t_select_query_failures() {
  setup
  L_issue_ai_plan_approved="20"; L_issue_ai_ready="12"
  QF_list_pr_ai_reviewing=1
  out=$(select_action); rc=$?
  assert_eq "reviewing 조회 실패: 출력 없음" "" "$out"
  assert_rc "reviewing 조회 실패: non-zero" 1 $rc
  QF_list_pr_ai_reviewing=""
  QF_list_issue_ai_implementing=1
  out=$(select_action); rc=$?
  assert_eq "implementing 조회 실패: 출력 없음" "" "$out"; assert_rc "implementing 조회 실패" 1 $rc
  QF_list_issue_ai_implementing=""
  assert_eq "정상이면 implement" "implement 20" "$(select_action)"

  # 선행 이슈 본문/상태 조회 실패 -> 후보 아님, 조회 오류
  L_issue_ai_plan_approved=""; L_issue_ai_ready="12 13"
  B_12="선행: #5"; QF_body_12=1
  out=$(select_action); rc=$?
  assert_eq "본문 조회 실패: 선택 안 됨" "" "$out"; assert_rc "본문 조회 실패" 1 $rc
  QF_body_12=""; QF_state_5=1
  out=$(select_action); rc=$?
  assert_eq "상태 조회 실패: 선택 안 됨" "" "$out"; assert_rc "상태 조회 실패" 1 $rc
  deps_closed 12; assert_rc "deps_closed 조회 오류 = 2" 2 $?
  first_ready ai:ready >/dev/null; assert_rc "first_ready 조회 오류 = 2" 2 $?
  QF_state_5=""; S_5=CLOSED
  assert_eq "복구되면 선택" "plan 12" "$(select_action)"

  # q_count 실패 -> propose 선택 안 됨
  L_issue_ai_ready=""
  QF_count_ai_ready=1
  out=$(select_action); rc=$?
  assert_eq "q_count 실패: propose 아님" "" "$out"; assert_rc "q_count 실패" 1 $rc
  propose_due; assert_rc "propose_due 조회 오류 = 2" 2 $?
  QF_count_ai_ready=""
  assert_eq "정상이면 propose" "propose -" "$(select_action)"
  # plan-revise 조회 실패
  QF_list_issue_ai_plan_revise=1
  out=$(select_action); rc=$?
  assert_eq "plan-revise 조회 실패" "" "$out"; assert_rc "plan-revise 조회 실패 rc" 1 $rc
}

t_run_stage_fetch() {
  setup; STOP_FILE="$LOG_DIR/STOP"; FETCH_FAIL_STOP=3
  fake_claude_env "PIPELINE_RESULT: pr 250" 0
  GIT_RC=1
  run_stage implement 100 >/dev/null; assert_rc "fetch 1회 실패 -> 1" 1 $?
  assert_eq "라벨 호출 없음" "" "$(label_calls)"
  [ ! -f "$STOP_FILE" ]; assert_rc "1회: STOP 없음" 0 $?
  assert_eq "카운터 1" "1" "$(cat "$LOG_DIR/fetch-failures")"
  run_stage implement 100 >/dev/null
  [ ! -f "$STOP_FILE" ]; assert_rc "2회: STOP 없음" 0 $?
  run_stage implement 100 >/dev/null
  [ -f "$STOP_FILE" ]; assert_rc "3회 연속: STOP 생성" 0 $?
  assert_contains "STOP 사유" "$(cat "$STOP_FILE")" "fetch"
  assert_contains "STOP 알림" "$(calls)" "notify git fetch 3회 연속 실패"
  assert_eq "끝까지 라벨 호출 없음" "" "$(label_calls)"
  rm -f "$STOP_FILE"; GIT_RC=0
  run_stage plan 101 >/dev/null
  [ ! -f "$LOG_DIR/fetch-failures" ]; assert_rc "fetch 성공 -> 카운터 삭제" 0 $?
}

t_run_stage_pretransition_failure() {
  setup
  fake_claude_env "PIPELINE_RESULT: pr 250" 0
  run_claude() { echo "claude" >> "$CALLS"; echo '{}' > "$4"; return 0; }
  ADD_RC=1
  run_stage implement 110 >/dev/null; assert_rc "사전 전이 실패 -> 1" 1 $?
  assert_eq "claude 미실행" "" "$(grep '^claude' "$CALLS")"
  assert_eq "blocked 미부착" "" "$(grep 'ai:blocked' "$CALLS")"
  assert_contains "알림" "$(calls)" "notify 라벨 전이 실패"
}

t_run_stage_review_round() {
  setup; MAX_REVIEW_ROUNDS=3
  q_review_rounds() { echo "q_review_rounds" >> "$LOG_DIR/review-calls"; echo 1; }
  prepare_worktree() { mktemp -d; }
  run_claude() {
    stage_prompt "$2" "$3" > "$LOG_DIR/prompt"
    echo '{"result":"PIPELINE_RESULT: approved"}' > "$4"
    return 0
  }
  run_stage review 120 >/dev/null
  assert_contains "성공 시 다음 라운드를 프롬프트에 전달" "$(cat "$LOG_DIR/prompt")" "REVIEW_ROUND=2/3"
  assert_eq "프롬프트 생성 중 재조회 없음" "1" "$(wc -l < "$LOG_DIR/review-calls" | tr -d ' ')"

  : > "$CALLS"
  q_review_rounds() { echo 3; }
  run_claude() { echo "claude" >> "$CALLS"; return 0; }
  run_stage review 121 >/dev/null
  assert_eq "상한 도달 시 claude 미실행" "" "$(grep '^claude' "$CALLS")"
  assert_contains "상한 도달 시 needs-human 전이" "$(calls)" "add 121 ai:needs-human"

  : > "$CALLS"
  q_review_rounds() { return 1; }
  run_claude() { echo "claude" >> "$CALLS"; return 0; }
  run_stage review 122 >/dev/null; assert_rc "라운드 조회 실패 -> 1" 1 $?
  assert_eq "라운드 조회 실패 시 claude 미실행" "" "$(grep '^claude' "$CALLS")"
  assert_eq "라운드 조회 실패 시 라벨·코멘트 없음" "" "$(label_calls)"
}

t_main_query_failure() {
  local tmp bin pl out rc
  tmp=$(mktemp -d); bin="$tmp/bin"; pl="$tmp/tools/agent-pipeline"
  mkdir -p "$bin" "$pl"
  cp "$PIPELINE_DIR/dispatch.sh" "$PIPELINE_DIR/lib.sh" "$PIPELINE_DIR/config.env" "$pl/"
  sed -i.bak 's/^PIPELINE_ENABLED=.*/PIPELINE_ENABLED=true/; s/^NOTIFY=.*/NOTIFY=false/' "$pl/config.env"
  # 조회(gh issue/pr list)는 실패, 변경(gh api)은 호출되면 기록
  printf '#!/bin/bash\necho "gh $*" >> "%s/fake-calls"\nexit 1\n' "$tmp" > "$bin/gh"
  chmod +x "$bin/gh"
  out=$(PATH="$bin:$PATH" LOG_DIR="$tmp/logs" bash "$pl/dispatch.sh" 2>&1); rc=$?
  assert_rc "조회 실패해도 exit 0" 0 "$rc"
  assert_contains "재시도 로그" "$out" "GitHub 조회 실패"
  ! grep -q 'api' "$tmp/fake-calls"; assert_rc "라벨 변경(gh api) 없음" 0 $?
  out=$(PATH="$bin:$PATH" LOG_DIR="$tmp/logs" bash "$pl/dispatch.sh" --dry-run 2>&1); rc=$?
  assert_rc "dry-run 도 exit 0" 0 "$rc"
  assert_contains "dry-run 재시도 로그" "$out" "GitHub 조회 실패"
  rm -rf "$tmp"
}

# ---------- 10. gh-api 래퍼 ----------
t_gh_api_wrapper() {
  local tmp bin out rc
  tmp=$(mktemp -d); bin="$tmp/bin"; mkdir -p "$bin"
  printf '#!/bin/bash\necho "FAKE-GH $*"\nexit 0\n' > "$bin/gh"; chmod +x "$bin/gh"
  W="$PIPELINE_DIR/bin/gh-api"
  R=hkjbrian/gongu

  allow() { # <설명> <인자...>
    local d=$1; shift
    out=$(PATH="$bin:$PATH" REPO=$R "$W" "$@" 2>&1); rc=$?
    assert_rc "허용: $d" 0 "$rc"
    assert_contains "허용 실행: $d" "$out" "FAKE-GH api"
  }
  deny() {
    local d=$1; shift
    out=$(PATH="$bin:$PATH" REPO=$R "$W" "$@" 2>&1); rc=$?
    assert_rc "거부: $d" 3 "$rc"
    assert_contains "거부 메시지: $d" "$out" "gh-api: 거부됨"
    case "$out" in *FAKE-GH*) assert_eq "거부 시 gh 미실행: $d" "no" "yes" ;; esac
  }

  allow "GET issue comments" repos/$R/issues/12/comments
  allow "leading slash" /repos/$R/issues/12
  allow "paginate + jq" repos/$R/pulls/5/comments --paginate --jq '.[] | .body'
  allow "POST reply" -X POST repos/$R/pulls/5/comments/99/replies -f body=hi
  allow "POST issue comment -f" repos/$R/issues/12/comments -f body='안녕'
  allow "POST 필드만으로 추정" repos/$R/issues/12/comments -F body=x
  allow "쿼리스트링 GET" "repos/$R/milestones?state=open" --jq '.[].title'
  allow "pulls files" repos/$R/pulls/5/files --paginate
  allow "commit sha" repos/$R/commits/abc123def
  allow "issue comment by id" repos/$R/issues/comments/777
  allow "--method get" --method get repos/$R/labels

  deny "graphql" graphql -f query='{viewer{login}}'
  deny "PUT merge" -X PUT repos/$R/pulls/5/merge
  deny "PATCH issue" -X PATCH repos/$R/issues/5 -f state=closed
  deny "DELETE label" -X DELETE repos/$R/issues/5/labels/ai%3Aready
  deny "다른 저장소" repos/evil/other/issues/1
  deny "경로 .." repos/$R/issues/../../../user
  deny "POST labels" -X POST repos/$R/issues/5/labels -f 'labels[]=ai:ready'
  deny "POST comments + labels 필드" repos/$R/issues/5/comments -f body=x -f 'labels[]=x'
  deny "POST comments + state 필드" repos/$R/issues/5/comments -f body=x -F state=closed
  deny "소문자 -X post + 금지 경로" -X post repos/$R/issues/5/labels
  deny "소문자 -X post + merge" -X post repos/$R/pulls/5/merge
  deny "GET 금지 경로" repos/$R/contents/README.md
  deny "GET 라벨 변경성 경로" repos/$R/issues/5/labels
  deny "repos 아닌 경로" user
  deny "endpoint 없음" --paginate
  deny "--input" -X POST repos/$R/issues/5/comments --input body.json
  deny "인코딩된 .." "repos/$R/issues/%2e%2e/x"
  out=$(PATH="$bin:$PATH" "$W" repos/$R/issues 2>&1); rc=$?
  assert_rc "REPO 없으면 거부" 3 "$rc"
  rm -rf "$tmp"
}

# ---------- 8. main (별도 프로세스) ----------
t_main_disabled() {
  local tmp bin pl rc out
  tmp=$(mktemp -d); bin="$tmp/bin"; pl="$tmp/tools/agent-pipeline"
  mkdir -p "$bin" "$pl"
  # config.local.env 등 로컬 오버라이드가 영향을 주지 않도록 필수 파일만 복사
  cp "$PIPELINE_DIR/dispatch.sh" "$PIPELINE_DIR/lib.sh" "$PIPELINE_DIR/config.env" "$pl/"
  for c in gh claude gtimeout osascript git; do
    printf '#!/bin/bash\necho "%s $*" >> "%s/fake-calls"\nexit 99\n' "$c" "$tmp" > "$bin/$c"
    chmod +x "$bin/$c"
  done

  out=$(PATH="$bin:$PATH" LOG_DIR="$tmp/logs" bash "$pl/dispatch.sh" 2>&1); rc=$?
  assert_rc "PIPELINE_ENABLED=false 즉시 종료" 0 "$rc"
  assert_eq "출력 없음" "" "$out"
  [ ! -e "$tmp/fake-calls" ]; assert_rc "gh/claude 호출 없음" 0 $?
  [ ! -e "$tmp/logs/.lock" ]; assert_rc "락 생성 안 함" 0 $?

  # 활성화돼도 STOP 파일이 있으면 아무 호출 없이 종료
  sed -i.bak 's/^PIPELINE_ENABLED=.*/PIPELINE_ENABLED=true/' "$pl/config.env"
  touch "$pl/STOP"
  out=$(PATH="$bin:$PATH" LOG_DIR="$tmp/logs" bash "$pl/dispatch.sh" 2>&1); rc=$?
  assert_rc "STOP 파일 종료" 0 "$rc"
  [ ! -e "$tmp/fake-calls" ]; assert_rc "STOP: 호출 없음" 0 $?

  # 잘못된 인자
  PATH="$bin:$PATH" LOG_DIR="$tmp/logs" bash "$pl/dispatch.sh" --bogus >/dev/null 2>&1
  assert_rc "잘못된 인자 exit 2" 2 $?
  PATH="$bin:$PATH" LOG_DIR="$tmp/logs" bash "$pl/dispatch.sh" --stage bogus 1 >/dev/null 2>&1
  assert_rc "잘못된 stage exit 2" 2 $?
  rm -rf "$tmp"
}

for t in t_priority t_deps t_propose_due t_apply_plan t_apply_plan_auto t_apply_implement_review \
         t_apply_propose t_failures t_parse_result t_lock t_run_stage_plan t_run_stage_implement \
         t_run_stage_failures t_run_stage_env_error t_transition_failures t_label_rm_http \
         t_q_review_rounds_failure t_apply_transition_failure t_fail_label_errors \
         t_select_query_failures t_run_stage_fetch t_run_stage_pretransition_failure \
         t_run_stage_review_round \
         t_main_query_failure t_gh_api_wrapper t_main_disabled; do
  run_test "$t"
done

pass=$(grep -c '^P$' "$RESULTS"); fail=$(grep -c '^F$' "$RESULTS")
echo "----"
echo "PASS: $pass  FAIL: $fail"
[ "$fail" -eq 0 ] || exit 1
