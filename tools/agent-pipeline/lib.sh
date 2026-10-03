#!/bin/bash
# 에이전트 파이프라인 공용 함수. GitHub 조회(q_*)와 변경(m_*)을 분리해 테스트에서 교체할 수 있게 한다.
# macOS 기본 bash 3.2 호환을 유지한다 (연관 배열·mapfile 미사용).

# 디스패처가 대상에서 제외하는 라벨
SKIP_LABELS_JQ='["ai:paused","ai:blocked","ai:needs-human"]'

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# ---------- 조회 ----------
# q_* 는 gh 실패 시 non-zero 를 반환한다. 호출부는 "조회 오류"를 "결과 없음"과 구분해야 한다.

# q_list <issue|pr> <label> → 해당 라벨이 붙은 열린 대상 번호(오름차순), 제외 라벨이 붙은 것은 빠짐
q_list() {
  local enc out type_filter
  case "$1" in
    issue) type_filter='.pull_request == null' ;;
    pr) type_filter='.pull_request != null' ;;
    *) return 2 ;;
  esac
  enc=$(jq -rn --arg s "$2" '$s|@uri') || return $?
  # 공개 저장소에서 외부 작성 이슈는 라벨이 붙어도 선택하지 않는다. 작성자가 승인 후 본문을 바꿔
  # 지시를 주입하는 것을 막기 위해 외부 제안은 사람이 재작성해야 파이프라인에 진입한다.
  out=$(gh api "repos/$REPO/issues?state=open&labels=$enc&per_page=100" --paginate --jq \
    '.[] | select('"$type_filter"') | select(.author_association == "OWNER" or .author_association == "MEMBER" or .author_association == "COLLABORATOR") | select([.labels[].name] | any(. as $l | '"$SKIP_LABELS_JQ"' | index($l)) | not) | .number') || return $?
  [ -z "$out" ] || printf '%s\n' "$out" | sort -n
}

# q_count <label> → 해당 라벨이 붙은 열린 이슈 수 (제외 라벨 무관)
q_count() {
  gh issue list -R "$REPO" --state open --label "$1" --limit 200 --json number --jq 'length'
}

# q_unlabeled_proposals → 제안 마커는 있으나 ai: 라벨이 없는 OWNER 이슈 번호
q_unlabeled_proposals() {
  gh api "repos/$REPO/issues?state=open&per_page=100" --paginate --jq \
    '.[] | select(.pull_request == null) | select(.author_association == "OWNER") | select((.body // "") | contains("<!-- ai-proposed -->")) | select([.labels[].name | select(startswith("ai:"))] | length == 0) | .number'
}

q_body() { gh issue view "$1" -R "$REPO" --json body --jq '.body'; }

q_state() { gh issue view "$1" -R "$REPO" --json state --jq '.state'; }

q_labels() { gh issue view "$1" -R "$REPO" --json labels --jq '.labels[].name'; }

# q_review_rounds <pr> → 리뷰 라운드 마커 코멘트 수
q_review_rounds() {
  local out
  # 공개 저장소에서 외부인이 마커를 남겨 라운드 상한을 소모하지 못하도록 OWNER 코멘트만 센다.
  # 파이프 끝의 awk 가 gh 의 종료 코드를 삼키지 않도록 먼저 변수로 받는다 (페이지별로 숫자가 한 줄씩 나온다)
  out=$(gh api "repos/$REPO/issues/$1/comments" --paginate \
    --jq '[.[] | select((.body | contains("<!-- ai-review-round -->")) and .author_association == "OWNER")] | length') || return 1
  printf '%s\n' "$out" | awk '{s+=$1} END {print s+0}'
}

# q_pr_branch <pr> → head 브랜치명
q_pr_branch() { gh pr view "$1" -R "$REPO" --json headRefName --jq '.headRefName'; }

# ---------- 변경 (DRY_RUN=1 이면 출력만) ----------
# 실패하면 non-zero. 호출부(transition·apply_result)가 전파한다.

m_label_add() {
  if [ "${DRY_RUN:-0}" = 1 ]; then log "DRY: label +$2 #$1"; return 0; fi
  gh api -X POST "repos/$REPO/issues/$1/labels" -f "labels[]=$2" >/dev/null || return 1
}

m_label_rm() {
  if [ "${DRY_RUN:-0}" = 1 ]; then log "DRY: label -$2 #$1"; return 0; fi
  local enc out
  enc=$(jq -rn --arg s "$2" '$s|@uri')
  # 라벨이 이미 없어서 나는 404 만 성공으로 본다. 그 외(인증·네트워크·5xx)는 실패로 전파
  out=$(gh api -X DELETE "repos/$REPO/issues/$1/labels/$enc" 2>&1) && return 0
  case "$out" in *"HTTP 404"*) return 0 ;; esac
  return 1
}

m_comment() {
  if [ "${DRY_RUN:-0}" = 1 ]; then log "DRY: comment #$1: $2"; return 0; fi
  gh api -X POST "repos/$REPO/issues/$1/comments" -f body="$2" >/dev/null || return 1
}

m_notify() {
  [ "${NOTIFY:-false}" = true ] || return 0
  [ "${DRY_RUN:-0}" = 1 ] && return 0
  # 메시지를 argv 로 넘겨 따옴표가 섞여도 AppleScript 문법이 깨지지 않게 한다
  osascript -e 'on run argv' -e 'display notification (item 1 of argv) with title "gongu 파이프라인"' -e 'end run' \
    "$1" >/dev/null 2>&1 || true
}

# 제안 도중 종료되어 결과 줄에 잡히지 않은 이슈의 상태 라벨을 복구한다.
recover_unlabeled_proposals() {
  local numbers n recovered="" failed=0
  numbers=$(q_unlabeled_proposals) || return 1
  for n in $numbers; do
    if m_label_add "$n" ai:proposed; then
      recovered="$recovered${recovered:+ }$n"
    else
      failed=1
    fi
  done
  [ -z "$recovered" ] || m_notify "라벨 없는 제안 이슈 복구: $recovered"
  [ "$failed" = 0 ]
}

# ---------- 파생 판단 ----------

# deps_of <issue> → 본문 '선행:' 줄의 이슈 번호들. 본문 조회 실패 시 return 2
deps_of() {
  local body
  body=$(q_body "$1") || return 2
  printf '%s\n' "$body" | grep -E '^[[:space:]*-]*선행[[:space:]*]*:' | grep -oE '#[0-9]+' | tr -d '#'
  return 0
}

# deps_closed <issue> → 선행 이슈가 모두 CLOSED 면 0, 미완료면 1, 조회 오류면 2
deps_closed() {
  local d deps s
  deps=$(deps_of "$1") || return 2
  for d in $deps; do
    s=$(q_state "$d") || return 2
    [ "$s" = CLOSED ] || return 1
  done
  return 0
}

# first_ready <label> → 해당 라벨 이슈 중 선행 조건을 만족하는 가장 오래된 번호. 후보 없음 1, 조회 오류 2
first_ready() {
  local n list rc
  list=$(q_list issue "$1") || return 2
  for n in $list; do
    deps_closed "$n"; rc=$?
    if [ "$rc" = 0 ]; then echo "$n"; return 0; fi
    [ "$rc" = 2 ] && return 2
  done
  return 1
}

# has_label <issue> <label> — 조회 실패도 false (호출부는 안전한 기본 동작으로 떨어진다)
has_label() {
  local labels
  labels=$(q_labels "$1") || return 2
  printf '%s\n' "$labels" | grep -qx "$2"
}

# transition <번호> <제거할 라벨> <추가할 라벨>
# add 를 먼저, rm 을 나중에: 중간에 실패해도 상태 라벨이 사라지는 대신 중복으로 남는다. 어느 쪽이든 실패하면 return 1
transition() {
  local rollback_failed message
  if [ -n "$3" ]; then m_label_add "$1" "$3" || return 1; fi
  if [ -n "$2" ] && ! m_label_rm "$1" "$2"; then
    [ -n "$3" ] || return 1
    rollback_failed=0
    # rm 응답만 유실돼 이전 라벨이 실제로 지워졌어도 새 라벨 제거 후 이전 라벨을 재추가하면 이전 상태만 남는다.
    m_label_rm "$1" "$3" || rollback_failed=1
    m_label_add "$1" "$2" || rollback_failed=1
    [ "$rollback_failed" = 0 ] && return 1
    message="label state mismatch #$1 (+$3/-$2)"
    [ -z "${STOP_FILE:-}" ] || printf '%s\n' "$message" > "$STOP_FILE"
    m_notify "$message"
    return 1
  fi
  return 0
}

# env_error_of <claude json 출력 파일> <종료 코드> → 환경 오류(인증 만료·API 장애·CLI 실행 실패)면 사유 출력 후 0
# 이슈 내용과 무관한 실패이므로 이슈에 blocked 를 붙이지 않고 파이프라인 전체를 멈추는 데 쓴다
env_error_of() {
  local reason
  reason=$(jq -r 'select(.is_error == true and .terminal_reason == "api_error") | .result' "$1" 2>/dev/null)
  if [ -n "$reason" ]; then echo "$reason"; return 0; fi
  if [ ! -s "$1" ] && [ "$2" != 0 ] && [ "$2" != 124 ]; then echo "claude 실행 실패 (exit $2, 출력 없음)"; return 0; fi
  return 1
}

# parse_result <claude json 출력 파일> → PIPELINE_RESULT 줄의 값 (없으면 빈 문자열)
parse_result() {
  jq -r '.result // empty' "$1" 2>/dev/null \
    | grep -E '^[[:space:]`]*PIPELINE_RESULT:' | tail -1 \
    | sed -E 's/^[[:space:]`]*PIPELINE_RESULT:[[:space:]]*//; s/[`[:space:]]*$//'
}
