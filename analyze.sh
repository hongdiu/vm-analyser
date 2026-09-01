#!/usr/bin/env bash
# =====================================================================
# analyze.sh — jvm-heap-analyzer 启动脚本（Linux / macOS / Windows Git-Bash）
#
# 1) 自动推算 JVM 堆：解析阶段内存随【对象数量】增长，经验公式
#    堆 ≈ 转储GB × 2.5 + 1（下限 4g，上限物理内存 60%）。
#    例：6GB/9300万对象 → 16g。对象密度特别高的转储若仍 GC 风暴，
#    用 XMX_OVERRIDE=20g 手动加大。
# 2) -b 后台模式：nohup + analyze.log，防 ssh 断连丢进程。
# 3) 大转储(>=4GB)自动收紧 --top/--min-retained-mb（显式传参优先）。
#
# 用法:
#   ./analyze.sh -i dump.hprof -o report/
#   ./analyze.sh -b -i dump.hprof -o report/        # 后台 + 日志
#   ./analyze.sh -i dump.hprof -o report/ --top 5 --min-retained-mb 50
#
# 环境变量: JAR / JAVA / XMX_OVERRIDE(如 16g) / JAVA_OPTS / LOG(日志路径)
# =====================================================================
set -eu
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------- 定位 jar：build/libs 优先，其次脚本同目录（便携部署） ----------
if [ -z "${JAR:-}" ]; then
  JAR="$(ls "$SCRIPT_DIR"/build/libs/jvm-heap-analyzer-*.jar 2>/dev/null | sort -V | tail -1 || true)"
  if [ -z "$JAR" ]; then
    JAR="$(ls "$SCRIPT_DIR"/jvm-heap-analyzer-*.jar 2>/dev/null | sort -V | tail -1 || true)"
  fi
fi
if [ -z "${JAR:-}" ] || [ ! -f "$JAR" ]; then
  echo "[analyze] fat-jar not found. Build first: ./gradlew build  (or set JAR=/path/to/jar)" >&2
  exit 2
fi
JAVA="${JAVA:-java}"

# ---------- 解析参数：摘出 -b，其余透传 ----------
BG=0
ARGS=()
DUMP=""
prev=""
for a in "$@"; do
  if [ "$a" = "-b" ] || [ "$a" = "--bg" ]; then BG=1; continue; fi
  if [ "${prev:-}" = "-i" ] || [ "${prev:-}" = "--input" ]; then DUMP="$a"; fi
  prev="$a"
  ARGS+=("$a")
done

# ---------- 零参数支持：-i 缺省取脚本目录最新 hprof；-o 缺省当前目录 ----------
has_i=0; has_o=0
for a in ${ARGS[@]+"${ARGS[@]}"}; do
  case "$a" in -i|--input) has_i=1;; -o|--output) has_o=1;; esac
done
if [ "$has_i" = "0" ]; then
  found="$(ls -t "$SCRIPT_DIR"/*.hprof "$SCRIPT_DIR"/*.hprof.gz 2>/dev/null | head -1 || true)"
  if [ -z "$found" ]; then
    echo "[analyze] no -i given and no *.hprof found in $SCRIPT_DIR" >&2
    exit 2
  fi
  echo "[analyze] -i not given, auto-picked newest hprof: $found" >&2
  ARGS+=(-i "$found")
  DUMP="$found"
fi
if [ "$has_o" = "0" ]; then
  echo "[analyze] -o not given, report -> current directory" >&2
  ARGS+=(-o .)
fi

# ---------- 堆推算 ----------
xms_g=1
if [ -n "${XMX_OVERRIDE:-}" ]; then
  XMX_ARGS=(-Xmx"${XMX_OVERRIDE}" -Xms1g)
else
  xmx_g=4
  if [ -n "$DUMP" ] && [ -f "$DUMP" ]; then
    if size_bytes=$(stat -c %s "$DUMP" 2>/dev/null); then :
    elif size_bytes=$(stat -f %z "$DUMP" 2>/dev/null); then :
    else size_bytes=$(wc -c < "$DUMP"); fi
    size_g=$(( (size_bytes + 1073741823) / 1073741824 ))   # 向上取整
    want=$(( size_g * 5 / 2 + 1 ))
    ram_g=0
    if [ -r /proc/meminfo ]; then
      ram_g=$(( $(awk '/MemTotal/{print $2; exit}' /proc/meminfo) / 1048576 ))
    elif command -v sysctl >/dev/null 2>&1; then
      ram_b=$(sysctl -n hw.memsize 2>/dev/null || echo 0); ram_g=$(( ram_b / 1073741824 ))
    fi
    if [ "$ram_g" -gt 0 ]; then
      cap=$(( ram_g * 60 / 100 )); [ "$cap" -lt 4 ] && cap=4
      xmx_g=$(( want < cap ? want : cap ))
    else
      xmx_g=$want
    fi
    [ "$xmx_g" -lt 4 ] && xmx_g=4
    xms_g=$(( xmx_g < 8 ? 1 : xmx_g / 4 ))
    echo "[analyze] dump~${size_g}G ram~${ram_g}G -> -Xmx${xmx_g}g (formula: dumpGB*2.5+1, cap=RAM*60%)" >&2
  else
    echo "[analyze] dump size unknown -> default -Xmx4g" >&2
  fi
  XMX_ARGS=(-Xmx"${xmx_g}"g -Xms"${xms_g}"g)
fi

# ---------- 大转储(>=4GB)友好默认：用户显式传参优先 ----------
EXTRA=()
if [ -n "$DUMP" ] && [ -f "$DUMP" ]; then
  sz=$(wc -c < "$DUMP" 2>/dev/null || echo 0)
  if [ "$sz" -ge 4294967296 ]; then
    case " ${ARGS[*]:-} " in *" --top "*) : ;; *) EXTRA+=(--top 10) ;; esac
    case " ${ARGS[*]:-} " in *--min-retained-mb*) : ;; *) EXTRA+=(--min-retained-mb 20) ;; esac
  fi
fi

CMD=("$JAVA" "${XMX_ARGS[@]}" -XX:+UseG1GC ${JAVA_OPTS:-} -jar "$JAR" "${ARGS[@]}" ${EXTRA[@]+"${EXTRA[@]}"})

# ---------- 后台模式 ----------
if [ "$BG" = "1" ]; then
  LOG="${LOG:-analyze.log}"
  nohup "${CMD[@]}" > "$LOG" 2>&1 &
  pid=$!
  echo "[analyze] background started pid=$pid log=$LOG"
  echo "[analyze] tail -f $LOG"
  echo "[analyze] 心跳每 60s 打印已耗时与索引写入量；数值在涨即正常，勿轻易 kill"
  exit 0
fi

exec "${CMD[@]}"
