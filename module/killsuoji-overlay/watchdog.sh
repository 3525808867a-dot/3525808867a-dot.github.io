#!/system/bin/sh
STATE_DIR=/data/adb/killsuoji_overlay
ENABLED="$STATE_DIR/enabled"
SERVICE_PIDFILE="$STATE_DIR/watchdog.pid"
WINDOW_PIDFILE="$STATE_DIR/root-window.pid"

mkdir -p "$STATE_DIR"
if [ -f "$SERVICE_PIDFILE" ]; then
  OLD_PID=$(cat "$SERVICE_PIDFILE" 2>/dev/null)
  if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
    exit 0
  fi
fi

echo $$ > "$SERVICE_PIDFILE"
trap 'rm -f "$SERVICE_PIDFILE"; if [ -f "$WINDOW_PIDFILE" ]; then kill "$(cat "$WINDOW_PIDFILE" 2>/dev/null)" 2>/dev/null; rm -f "$WINDOW_PIDFILE"; fi' EXIT

start_root_window() {
  APK_PATH=$(pm path com.killsuoji.app 2>/dev/null | head -n 1 | cut -d: -f2)
  [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ] || return 1
  CLASSPATH="$APK_PATH" app_process /system/bin com.killsuoji.app.RootWindowMain >/dev/null 2>&1 &
  echo $! > "$WINDOW_PIDFILE"
}

while [ -f "$ENABLED" ]; do
  if ! pidof com.killsuoji.app:overlay >/dev/null 2>&1; then
    am start-foreground-service -n com.killsuoji.app/.RootVolumeMonitorService >/dev/null 2>&1
  fi

  WINDOW_PID=""
  [ -f "$WINDOW_PIDFILE" ] && WINDOW_PID=$(cat "$WINDOW_PIDFILE" 2>/dev/null)
  if [ -z "$WINDOW_PID" ] || ! kill -0 "$WINDOW_PID" 2>/dev/null; then
    rm -f "$WINDOW_PIDFILE"
    start_root_window
  fi
  sleep 3
done
