#!/system/bin/sh
STATE_DIR=/data/adb/killsuoji_overlay
ENABLED="$STATE_DIR/enabled"
PIDFILE="$STATE_DIR/watchdog.pid"

mkdir -p "$STATE_DIR"
if [ -f "$PIDFILE" ]; then
  OLD_PID=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
    exit 0
  fi
fi

echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"' EXIT

while [ -f "$ENABLED" ]; do
  if ! pidof com.killsuoji.app:overlay >/dev/null 2>&1; then
    am start-foreground-service -n com.killsuoji.app/.RootVolumeMonitorService >/dev/null 2>&1
  fi
  sleep 3
done
