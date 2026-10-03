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
_k() { echo "${1//[^a-zA-Z0-9]/_}"; }

setup() {
  LOG_DIR=$(mktemp -d)
  export LOG_DIR
  . "$PIPELINE_DIR/dispatch.sh"
  set +u
  CALLS="$LOG_DIR/calls"; : > "$CALLS"
  RR=0

  q_list()  { local v="L_$1_$(_k "$2")"; local x="${!v}"; [ -n "$x" ] && printf '%s\n' $x; return 0; }
  q_count() { local v="C_$(_k "$1")"; echo "${!v}"; }
  q_body()  { local v="B_$1"; printf '%s\n' "${!v}"; }
  q_state() { local v="S_$1"; echo "${!v:-OPEN}"; }
  q_labels(){ local v="LB_$1"; printf '%s\n' ${!v}; }
  q_review_rounds() { echo "$RR"; }
  q_pr_branch() { local v="BR_$1"; echo "${!v}"; }

  m_label_add() { echo "add $1 $2" >> "$CALLS"; }
  m_label_rm()  { echo "rm $1 $2" >> "$CALLS"; }
  m_comment()   { echo "comment $1 $(printf '%s' "$2" | tr '\n' ' ')" >> "$CALLS"; }
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
  assert_eq "plan planned" "rm 30 ai:ready${nl}add 30 ai:plan-review${nl}notify #30 계획 검토 대기" "$(calls)"
  : > "$CALLS"
  apply_result replan 31 planned 0 /dev/null >/dev/null
  assert_eq "replan planned" "rm 31 ai:plan-revise${nl}add 31 ai:plan-review${nl}notify #31 계획 검토 대기" "$(calls)"
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
  assert_eq "docs 라벨 자동 승인" "rm 40 ai:ready${nl}add 40 ai:plan-approved" "$(calls)"
  : > "$CALLS"
  LB_41="feature ai:ready"
  apply_result plan 41 planned 0 /dev/null >/dev/null
  assert_contains "docs 아니면 plan-review" "$(calls)" "add 41 ai:plan-review"
}

t_apply_implement_review() {
  setup
  apply_result implement 7 "pr 250" 0 /dev/null >/dev/null
  assert_eq "implement pr" "rm 7 ai:implementing${nl}add 7 ai:in-pr${nl}add 250 ai:reviewing" "$(calls)"
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
  assert_eq "run_stage plan" "rm 60 ai:ready${nl}add 60 ai:plan-review${nl}notify #60 계획 검토 대기" "$(calls)"
  assert_contains "runs.log 기록" "$(cat "$LOG_DIR/runs.log")" "plan 60 rc=0 planned"
}

t_run_stage_implement() {
  setup
  fake_claude_env "PIPELINE_RESULT: pr 250" 0
  run_stage implement 7 >/dev/null
  assert_eq "run_stage implement" \
    "rm 7 ai:plan-approved${nl}add 7 ai:implementing${nl}rm 7 ai:implementing${nl}add 7 ai:in-pr${nl}add 250 ai:reviewing" "$(calls)"
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
         t_run_stage_failures t_main_disabled; do
  run_test "$t"
done

pass=$(grep -c '^P$' "$RESULTS"); fail=$(grep -c '^F$' "$RESULTS")
echo "----"
echo "PASS: $pass  FAIL: $fail"
[ "$fail" -eq 0 ] || exit 1
