#!/system/bin/sh
MODDIR=${0%/*}
STATE_DIR=/data/adb/killsuoji_overlay
ENABLED="$STATE_DIR/enabled"
PIDFILE="$STATE_DIR/watchdog.pid"

# Wait until Android has completed booting.
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

# Restore the watchdog after reboot only if the user had enabled Work mode.
if [ -f "$ENABLED" ] && [ -f "$MODDIR/watchdog.sh" ]; then
  mkdir -p "$STATE_DIR"
  if [ -f "$PIDFILE" ]; then
    OLD_PID=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
      exit 0
    fi
  fi
  chmod 700 "$MODDIR/watchdog.sh"
  nohup sh "$MODDIR/watchdog.sh" >/dev/null 2>&1 </dev/null &
fi
