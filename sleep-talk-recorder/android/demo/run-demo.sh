#!/usr/bin/env bash
# Scripted demo on an Android emulator (run from sleep-talk-recorder/android).
#  1. Records a prepared "night" (demo/night.wav played instead of the mic): three phrases and snoring.
#  2. Checks the automatic start: schedules the night two minutes ahead, turns the screen off
#     and waits for the app to switch the microphone on by itself.
# Screenshots, a screen recording and a log go to demo-out/.
set -u
PKG=io.github.iamdimitriy.sonnik
OUT=demo-out
mkdir -p "$OUT"
LOG="$OUT/demo-log.txt"
: > "$LOG"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; log "screenshot $1"; }
app() { adb shell am start -n "$PKG/sonnik.app.MainActivity" "$@" > /dev/null; }
service_running() { adb shell dumpsys activity services "$PKG" | grep -q "RecorderService"; }
# Taps the centre of the first view whose text or description matches $1.
tap_on() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  local xy
  xy=$(adb exec-out cat /sdcard/ui.xml | python3 -c '
import re, sys
xml, want = sys.stdin.read(), sys.argv[1]
for node in re.findall(r"<node [^>]*>", xml):
    text = re.search(r" text=\"([^\"]*)\"", node).group(1)
    desc = re.search(r" content-desc=\"([^\"]*)\"", node).group(1)
    if want in (text, desc):
        x1, y1, x2, y2 = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$1")
  if [ -n "$xy" ]; then adb shell input tap $xy; log "tap '$1' at $xy"; else log "no '$1' on screen"; fi
}

log "install"
adb install -r app/build/outputs/apk/debug/app-debug.apk >> "$LOG" 2>&1
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG USE_FULL_SCREEN_INTENT allow || true
adb shell dumpsys deviceidle whitelist +$PKG >> "$LOG"
adb shell settings put system screen_off_timeout 600000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

log "copy the demo night into the app"
adb push demo/night.wav /data/local/tmp/night.wav > /dev/null
adb shell "cat /data/local/tmp/night.wav | run-as $PKG sh -c 'mkdir -p files && cat > files/night.wav'"
adb shell run-as $PKG ls -l files >> "$LOG" 2>&1

# ---------- Part 1: a night of sleep talk ----------
app
sleep 5
shot 01-home

adb shell screenrecord --time-limit 150 --bit-rate 3000000 /sdcard/demo.mp4 &
REC=$!
sleep 2
log "start recording the demo night"
app --es demo_wav night.wav
sleep 4
shot 02-listening
sleep 14
shot 03-first-phrase
adb shell cmd statusbar expand-notifications
sleep 3
shot 04-notification
adb shell cmd statusbar collapse

for _ in $(seq 1 60); do service_running || break; sleep 2; done
log "night finished: service running? $(service_running && echo yes || echo no)"
sleep 2
shot 05-morning
adb shell cmd statusbar expand-notifications
sleep 3
shot 06-morning-notification
adb shell cmd statusbar collapse
app --ez records true
sleep 3
shot 07-records
tap_on "Слушать"
sleep 2
shot 08-playing
sleep 6
wait $REC
adb pull /sdcard/demo.mp4 "$OUT/demo.mp4" >> "$LOG" 2>&1
adb shell run-as $PKG ls -lR files/nights >> "$LOG" 2>&1
# adb joins its arguments with spaces, so the remote command is quoted as one string.
CLIPS=$(adb shell "run-as $PKG sh -c 'ls files/nights/*/*.wav'" 2>/dev/null | grep -c '\.wav')
log "clips kept from the demo night: $CLIPS (expected 3 phrases, snoring ignored)"

# ---------- Part 2: automatic start with the screen off ----------
log "schedule tonight two minutes from now, then lock the phone"
app --ez records false --ei demo_schedule_in 2 --ei demo_length 3
sleep 4
shot 09-scheduled
adb shell dumpsys alarm | grep -A3 "$PKG" | head -20 >> "$LOG"
adb shell svc power stayon false
adb shell input keyevent KEYCODE_SLEEP
sleep 2
log "screen state: $(adb shell dumpsys power | grep -m1 'mWakefulness=')"

STARTED=""
for i in $(seq 1 90); do
  if service_running; then STARTED="after ~$((i * 2)) s"; break; fi
  sleep 2
done
log "auto-start: ${STARTED:-NOT STARTED}"
adb shell dumpsys activity services "$PKG" | grep -E "ServiceRecord|isForeground|foregroundServiceType" >> "$LOG"
sleep 70 # past the start of the window: WAITING -> RECORDING
adb shell dumpsys audio | grep -iE "AudioRecordingConfiguration|silenced|$PKG" | head -20 >> "$LOG"
adb shell dumpsys activity services "$PKG" | grep -E "ServiceRecord|isForeground" >> "$LOG"
adb shell input keyevent KEYCODE_WAKEUP
sleep 2
shot 10-lockscreen
adb shell cmd statusbar expand-notifications
sleep 3
shot 11-lockscreen-notification
adb shell cmd statusbar collapse
adb shell wm dismiss-keyguard
app
sleep 3
shot 12-auto-recording
adb logcat -d > "$OUT/logcat.txt" 2>&1
grep -E "Sonnik|FullScreen|FSI|$PKG" "$OUT/logcat.txt" > "$OUT/logcat-app.txt"
log "done"

FAILED=0
[ "$CLIPS" = 3 ] || { log "FAIL: expected 3 clips, got $CLIPS"; FAILED=1; }
[ -n "$STARTED" ] || { log "FAIL: recording did not start by itself"; FAILED=1; }
exit $FAILED
