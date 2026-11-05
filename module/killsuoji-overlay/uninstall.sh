#!/system/bin/sh
STATE_DIR=/data/adb/killsuoji_overlay
rm -f "$STATE_DIR/enabled"
for PIDFILE in "$STATE_DIR/watchdog.pid" "$STATE_DIR/root-window.pid"; do
  if [ -f "$PIDFILE" ]; then
    PID=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$PID" ] && kill "$PID" 2>/dev/null
    rm -f "$PIDFILE"
  fi
done
