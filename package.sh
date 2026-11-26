#!/usr/bin/env bash
# =====================================================================
# package.sh — 一键打出可运行的分发包（Linux / macOS / Git-Bash）
#
# 产物：
#   dist/jvm-heap-analyzer-<version>/            便携目录（jar + 启动脚本 + 说明）
#   dist/jvm-heap-analyzer-<version>.tar.gz     可直接拷到服务器解压使用
#   dist/jvm-heap-analyzer-<version>.zip        存在 zip 命令时同时生成
#
# 用法：
#   ./package.sh                 # 跑测试 + shadowJar + 打包
#   SKIP_TESTS=1 ./package.sh    # 跳过测试
#   CLEAN=1 ./package.sh         # 先 clean
#   JDK_HOME=/path/to/jdk17 ./package.sh
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# ---------- 1. 定位 JDK 17 ----------
is_jdk17() {
  [ -n "${1:-}" ] && [ -x "$1/bin/javac" ] || return 1
  if [ -f "$1/release" ]; then
    grep -q 'JAVA_VERSION="17' "$1/release" || return 1
  fi
  return 0
}

JDK="${JDK_HOME:-${JAVA_HOME:-}}"
if ! is_jdk17 "$JDK"; then
  JDK=""
  for c in \
      /usr/lib/jvm/*17* \
      /usr/lib/jvm/*-17-* \
      /opt/jdk*17* \
      /opt/*jdk*17* \
      /Library/Java/JavaVirtualMachines/*17*/Contents/Home \
      "$HOME"/.jdks/*17*; do
    if is_jdk17 "$c"; then JDK="$c"; break; fi
  done
fi
if [ -z "$JDK" ]; then
  echo "[package] 未找到 JDK 17。请安装后重试，或用 JDK_HOME=/path/to/jdk17 ./package.sh" >&2
  exit 1
fi
export JAVA_HOME="$JDK"
echo "[package] JAVA_HOME=$JAVA_HOME"

# ---------- 2. 构建 ----------
if [ "${CLEAN:-0}" = "1" ]; then
  echo "[package] gradlew clean ..."
  ./gradlew clean --console=plain
fi
if [ "${SKIP_TESTS:-0}" != "1" ]; then
  echo "[package] gradlew test ..."
  ./gradlew test --console=plain
fi
echo "[package] gradlew shadowJar ..."
./gradlew shadowJar --console=plain

# ---------- 3. 组装 dist ----------
JAR="$(ls -t build/libs/jvm-heap-analyzer-*.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ]; then
  echo "[package] 未找到 build/libs/jvm-heap-analyzer-*.jar" >&2
  exit 1
fi
VERSION="$(sed -n "s/^version[[:space:]]*=[[:space:]]*'\(.*\)'.*/\1/p" build.gradle | head -1)"
VERSION="${VERSION:-1.0.0}"

DIST_ROOT="$ROOT/dist"
DIST_DIR="$DIST_ROOT/jvm-heap-analyzer-$VERSION"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

cp "$JAR" "$DIST_DIR/"
cp analyze.sh analyze.bat "$DIST_DIR/"
chmod +x "$DIST_DIR/analyze.sh"

cat > "$DIST_DIR/USAGE.txt" <<EOF
jvm-heap-analyzer $VERSION 便携包
=====================================================

运行环境：JDK 17+（无需 Gradle / Maven / 联网）

Windows
-------
  analyze.bat -i D:\\dumps\\oom.hprof -o report --app-prefix com.mycorp
  analyze.bat -b -i D:\\dumps\\oom.hprof -o report --app-prefix com.mycorp
  查看进度：type analyze.log

Linux / macOS
-------------
  chmod +x analyze.sh
  ./analyze.sh -i /data/oom.hprof -o report --app-prefix com.mycorp
  ./analyze.sh -b -i /data/oom.hprof -o report --app-prefix com.mycorp
  查看进度：tail -f analyze.log

参数要点
--------
  -i / --input          堆转储（.hprof），必填；零参数时自动取脚本同目录最新的 hprof
  -o / --output         报告输出目录，必填（缺省当前目录）
  --app-prefix          业务类包前缀（逗号分隔），用于从线程栈里挑出业务帧，
                        例如：com.mycorp,cn.example.order
  --top                 每类结果最多条数（默认 20；大堆自动 10）
  --min-retained-mb     泄漏嫌疑最小 retained（MB，默认 5；大堆自动 20）
  --baseline            可选，第二份更早的 dump，输出"类实例数/浅堆增长"证据
  --verbose             输出 MAT 解析细节

堆内存
------
  启动脚本按 转储GB × 2.5 + 1 自动推算 -Xmx（下限 4g，上限物理内存 60%）。
  如仍出现 GC 风暴：XMX_OVERRIDE=16g（Windows: set XMX_OVERRIDE=16g）。

报告
----
  report/index.html   诊断结论（代码线索 + 证据 + 规模 + 验证 + 可展开原始堆栈）
  report/summary.json 结构化结果（含 codeLocations / accumulated / confidence）
EOF

# ---------- 4. 压缩 ----------
TAR="$DIST_ROOT/jvm-heap-analyzer-$VERSION.tar.gz"
rm -f "$TAR"
tar -czf "$TAR" -C "$DIST_ROOT" "jvm-heap-analyzer-$VERSION"

ZIP=""
if command -v zip >/dev/null 2>&1; then
  ZIP="$DIST_ROOT/jvm-heap-analyzer-$VERSION.zip"
  rm -f "$ZIP"
  (cd "$DIST_ROOT" && zip -qr "$(basename "$ZIP")" "jvm-heap-analyzer-$VERSION")
fi

echo
echo "[package] 完成："
echo "  目录 : $DIST_DIR"
echo "  压缩 : $TAR"
if [ -n "$ZIP" ]; then echo "         $ZIP"; fi
echo
echo "使用示例："
echo "  $DIST_DIR/analyze.sh -i /data/oom.hprof -o report --app-prefix com.mycorp"
echo "  $DIST_DIR/analyze.sh -b -i /data/oom.hprof -o report --app-prefix com.mycorp"
