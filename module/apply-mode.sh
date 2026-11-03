#!/system/bin/sh
# K70 Turbo runtime governor controller
# Only writes existing CPUFreq scaling_governor sysfs nodes.
# No partition, boot image, voltage, thermal, or GPU writes.
MODDIR=/data/adb/modules/k70turbo_monitor
STATE=/data/adb/k70turbo_governor_backup
mkdir -p /data/adb
say(){ echo "$*"; }
mode="${1:-}"
case "$mode" in
  diagnose)
    say "=== 只读诊断（不会修改任何节点）==="
    say "身份：$(id 2>&1)"
    if command -v getenforce >/dev/null 2>&1; then say "SELinux：$(getenforce 2>&1)"; else say "SELinux：getenforce 不可用"; fi
    found=0
    for node in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do
      [ -e "$node" ] || continue
      found=$((found+1))
      dir=${node%/scaling_governor}
      say "--- $dir ---"
      say "权限：$(ls -l "$node" 2>&1)"
      say "当前 governor：$(cat "$node" 2>&1)"
      say "可用 governor：$(cat "$dir/scaling_available_governors" 2>&1)"
    done
    [ "$found" -gt 0 ] || say "未发现 CPUFreq scaling_governor 节点"
    exit 0
    ;;
  powersave|balanced|performance|restore) ;;
  *) say "用法：apply-mode.sh powersave|balanced|performance|restore"; exit 2 ;;
esac

if [ "$mode" = "restore" ]; then
  [ -f "$STATE" ] || { say "没有可恢复的原始调速器记录"; exit 1; }
  ok=0; fail=0
  while IFS='|' read -r node value; do
    case "$node" in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor) ;; *) continue ;; esac
    if [ -w "$node" ] && [ -n "$value" ]; then
      echo "$value" > "$node" 2>/dev/null && ok=$((ok+1)) || fail=$((fail+1))
    fi
  done < "$STATE"
  [ "$fail" -eq 0 ] && say "已恢复原始 CPU 调速器（$ok 个策略）" || say "恢复完成：成功 $ok，失败 $fail"
  [ "$fail" -eq 0 ]
  exit $?
fi

set -- /sys/devices/system/cpu/cpufreq/policy*/scaling_governor
[ -e "$1" ] || { say "未发现 CPUFreq governor 节点；本机内核接口不支持此项调节"; exit 1; }

[ -f "$STATE" ] || : > "$STATE"
ok=0; fail=0; skipped=0; details=""
for node in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do
  [ -e "$node" ] || continue
  [ -w "$node" ] || { skipped=$((skipped+1)); continue; }
  avail="${node%/scaling_governor}/scaling_available_governors"
  [ -r "$avail" ] || { skipped=$((skipped+1)); continue; }
  current=$(cat "$node" 2>/dev/null)
  if ! grep -Fq "$node|" "$STATE"; then
    printf '%s|%s\n' "$node" "$current" >> "$STATE"
  fi
  choices=$(cat "$avail" 2>/dev/null)
  target=""
  case "$mode" in
    powersave)
      for g in powersave schedutil ondemand conservative; do
        case " $choices " in *" $g "*) target="$g"; break;; esac
      done ;;
    balanced)
      for g in schedutil ondemand conservative powersave; do
        case " $choices " in *" $g "*) target="$g"; break;; esac
      done ;;
    performance)
      for g in performance schedutil ondemand; do
        case " $choices " in *" $g "*) target="$g"; break;; esac
      done ;;
  esac
  [ -n "$target" ] || { skipped=$((skipped+1)); continue; }
  errfile="/data/adb/k70turbo_write_error"
  if echo "$target" > "$node" 2>"$errfile" && [ "$(cat "$node" 2>/dev/null)" = "$target" ]; then
    ok=$((ok+1))
  else
    fail=$((fail+1))
    err="$(cat "$errfile" 2>/dev/null)"
    after="$(cat "$node" 2>&1)"
    details="$details
失败节点：$node
目标：$target；写入后读取：$after
写入错误：${err:-无 stderr（可能写入未生效或回读不一致）}"
  fi
done
say "模式：$mode；成功策略：$ok；跳过：$skipped；失败：$fail"
[ -n "$details" ] && say "$details"
[ "$ok" -gt 0 ] && [ "$fail" -eq 0 ]
