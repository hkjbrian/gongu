#!/bin/bash
# 에이전트 파이프라인 설치 — launchd plist 복사 및 안내

set -euo pipefail

PIPELINE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAUNCHD_DIR="$HOME/Library/LaunchAgents"
PLIST_SRC="$PIPELINE_DIR/com.gongu.agent-pipeline.plist"
PLIST_DST="$LAUNCHD_DIR/com.gongu.agent-pipeline.plist"
LOGS_DIR="$PIPELINE_DIR/logs"

echo "에이전트 파이프라인 설치"
echo "========================"
echo ""

# LaunchAgents 디렉터리 생성
if [ ! -d "$LAUNCHD_DIR" ]; then
  mkdir -p "$LAUNCHD_DIR"
  echo "✓ $LAUNCHD_DIR 생성"
fi

# plist 파일 복사
cp "$PLIST_SRC" "$PLIST_DST"
echo "✓ plist 파일 복사: $PLIST_DST"

# logs 디렉터리 생성
mkdir -p "$LOGS_DIR"
echo "✓ logs 디렉터리 생성: $LOGS_DIR"

echo ""
echo "다음 단계:"
echo "=========="
echo ""
echo "1. 파이프라인 시작:"
echo "   launchctl load $PLIST_DST"
echo ""
echo "2. 파이프라인 중지:"
echo "   launchctl unload $PLIST_DST"
echo ""
echo "3. 파이프라인 상태 확인:"
echo "   launchctl list com.gongu.agent-pipeline"
echo ""
echo "4. 로그 확인:"
echo "   tail -f $LOGS_DIR/launchd.out.log"
echo "   tail -f $LOGS_DIR/launchd.err.log"
echo ""
echo "주의: plist는 설치되었지만 load 되지 않았습니다."
echo "사람이 직접 'launchctl load' 명령으로 시작해야 합니다."
