#!/system/bin/sh
STATE_DIR=/data/adb/killsuoji_overlay
ENABLED="$STATE_DIR/enabled"
SERVICE_PIDFILE="$STATE_DIR/watchdog.pid"
WINDOW_PIDFILE="$STATE_DIR/root-window.pid"
LOGFILE="$STATE_DIR/root-window.log"

mkdir -p "$STATE_DIR"
log() { echo "[$(date '+%F %T' 2>/dev/null)] $*" >> "$LOGFILE"; }

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
  if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
    log "ERROR: cannot locate installed APK (pm path returned: $APK_PATH)"
    return 1
  fi
  APP_VERSION=$(dumpsys package com.killsuoji.app 2>/dev/null | grep -m 1 -E 'versionName=|versionCode=' | tr '\n' ' ')
  APK_HASH=$(sha256sum "$APK_PATH" 2>/dev/null | cut -d' ' -f1)
  log "Starting RootWindowMain; build=3.1.3-overlay-bitmap-single-thread; $APP_VERSION APK=$APK_PATH SHA256=$APK_HASH"
  # Remove stale native window instances before starting the current installed APK.
  OLD_PIDS=$(ps -A -o PID,ARGS 2>/dev/null | grep '[c]om.killsuoji.app.RootWindowMain' | awk '{print $1}')
  for OLD_PID in $OLD_PIDS; do
    [ "$OLD_PID" = "$" ] || kill "$OLD_PID" 2>/dev/null
  done
  sleep 1
  CLASSPATH="$APK_PATH" app_process /system/bin com.killsuoji.app.RootWindowMain >> "$LOGFILE" 2>&1 &
  WINDOW_PID=$!
  echo "$WINDOW_PID" > "$WINDOW_PIDFILE"
  sleep 2
  if kill -0 "$WINDOW_PID" 2>/dev/null; then
    log "RootWindowMain process alive; PID=$WINDOW_PID"
    return 0
  fi
  log "ERROR: RootWindowMain exited during startup; PID=$WINDOW_PID"
  rm -f "$WINDOW_PIDFILE"
  return 1
}

log "Watchdog started; UID=$(id -u)"
while [ -f "$ENABLED" ]; do
  if ! pidof com.killsuoji.app:overlay >/dev/null 2>&1; then
    log "Overlay service process missing; requesting Android service restart"
    am start-foreground-service -n com.killsuoji.app/.RootVolumeMonitorService >> "$LOGFILE" 2>&1
  fi

  WINDOW_PID=""
  [ -f "$WINDOW_PIDFILE" ] && WINDOW_PID=$(cat "$WINDOW_PIDFILE" 2>/dev/null)
  if [ -z "$WINDOW_PID" ] || ! kill -0 "$WINDOW_PID" 2>/dev/null; then
    rm -f "$WINDOW_PIDFILE"
    start_root_window
  fi
  sleep 3
done
log "Watchdog disabled; exiting"
