#!/system/bin/sh
STATE_DIR=/data/adb/killsuoji_overlay
PIDFILE="$STATE_DIR/watchdog.pid"
rm -f "$STATE_DIR/enabled"
if [ -f "$PIDFILE" ]; then
  PID=$(cat "$PIDFILE" 2>/dev/null)
  [ -n "$PID" ] && kill "$PID" 2>/dev/null
  rm -f "$PIDFILE"
fi
