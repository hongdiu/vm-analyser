#!/usr/bin/env bash
# =====================================================================
# build_test.sh — 全量构建 + 单元测试 + 端到端冒烟（一次跑完，日志落盘）
# 用法：在 git-bash / Linux 下执行  ./build_test.sh
# 退出码非 0 即失败；日志见 build-logs/
# =====================================================================
set -u
cd "$(dirname "$0")"
mkdir -p build-logs
LOG=build-logs/build_test.log
: > "$LOG"

echo "[1/3] gradlew clean build (compile + unit tests + fat jar) ..."
./gradlew.bat clean build --console=plain 2>&1 | tee -a "$LOG"
rc=${PIPESTATUS[0]}
if [ "$rc" -ne 0 ]; then
  echo "BUILD/TEST FAILED (rc=$rc), see $LOG"
  exit "$rc"
fi

echo "[2/3] CLI smoke test on sample hprof ..."
JAR=$(ls build/libs/jvm-heap-analyzer-*.jar | head -1)
java -Xmx1g -jar "$JAR" -i src/test/resources/sample-leak.hprof \
     -o build-logs/smoke-report --top 20 --max-path 8 --min-retained-mb 5 2>&1 | tee -a "$LOG"
rc=${PIPESTATUS[0]}
if [ "$rc" -ne 0 ]; then
  echo "CLI SMOKE FAILED (rc=$rc), see $LOG"
  exit "$rc"
fi

echo "[3/3] verify report artifacts ..."
for f in build-logs/smoke-report/index.html build-logs/smoke-report/summary.json build-logs/smoke-report/assets/style.css; do
  if [ ! -s "$f" ]; then
    echo "MISSING artifact: $f"
    exit 1
  fi
done
grep -q "内存泄漏嫌疑" build-logs/smoke-report/index.html || { echo "index.html missing expected section"; exit 1; }

echo "ALL PASSED. report: build-logs/smoke-report/index.html, log: $LOG"
