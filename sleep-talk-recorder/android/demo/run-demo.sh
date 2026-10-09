#!/usr/bin/env bash
# Scripted demo on an Android emulator (run from sleep-talk-recorder/android).
#  0. The first launch, before anything is allowed: walks the "Чтобы всё работало само" card.
#  1. Records a prepared "night" (demo/night.wav played instead of the mic): three phrases and snoring.
#  2. Checks the automatic start: schedules the night two minutes ahead, turns the screen off
#     and waits for the app to switch the microphone on by itself.
#  3. The alarm over the lock screen.  4. A dream written down after waking.
#  5. Large text and a small phone; a phone setting changing while a dream is being written.
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
UI="$OUT/ui.xml"
# Saves what is on screen now to $UI. The old dump is removed first so a failed dump is not reused.
dump_ui() {
  adb shell "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml" > /dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > "$UI" 2> /dev/null
}
# Prints the centre of the first view in $UI whose text or description is $1 (or contains it, with $2 = contains).
view_xy() {
  python3 -c '
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
want, mode = sys.argv[2], sys.argv[3]
for node in re.findall(r"<node [^>]*>", xml):
    text = re.search(r" text=\"([^\"]*)\"", node).group(1)
    desc = re.search(r" content-desc=\"([^\"]*)\"", node).group(1)
    if want in (text, desc) or (mode == "contains" and (want in text or want in desc)):
        x1, y1, x2, y2 = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$UI" "$1" "${2:-exact}"
}
# The emulator's own launcher sometimes stalls on CI, and its "isn't responding" dialog then
# covers everything; close it whenever a dump shows it.
fresh_ui() {
  dump_ui
  if grep -qE "isn.{1,6}t responding" "$UI"; then
    local xy
    xy=$(view_xy "Close app")
    [ -n "$xy" ] && adb shell input tap $xy
    log "closed a system 'isn't responding' dialog" >&2 # stdout is the caller's answer
    sleep 2
    dump_ui
  fi
}
find_view() { fresh_ui; view_xy "$@"; }
on_screen() { [ -n "$(find_view "$@")" ]; }
# Taps the view found by find_view; fails when there is none.
tap_on() {
  local xy
  xy=$(find_view "$@")
  if [ -n "$xy" ]; then adb shell input tap $xy; log "tap '$1' at $xy"; else log "no '$1' on screen"; return 1; fi
}
# Swipes up from y=$1 to y=$2 (pixels of the current display size).
scroll_down() { adb shell input swipe 300 "${1:-1600}" 300 "${2:-600}" 400; sleep 1; }

# The emulator's own launcher sometimes stalls on CI: keep "isn't responding" dialogs away from
# the start (they only stay hidden if this is set before they appear), and close one already up.
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure anr_show_background 0
adb shell settings put system screen_off_timeout 600000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
fresh_ui

log "install"
adb install -r app/build/outputs/apk/debug/app-debug.apk >> "$LOG" 2>&1

# ---------- Part 0: the first launch, nothing allowed yet ----------
# Every "Разрешить" opens Android's own question or settings page; answer it the way a person would.
app
sleep 6
shot 00a-first-launch
SETUP_STEPS=0
for step in 1 2 3 4 5 6; do
  tap_on "Разрешить" || break
  SETUP_STEPS=$step
  sleep 3
  shot "00b-setup-$step"
  tap_on "While using the app" || tap_on "Allow" || tap_on "ALLOW" || tap_on "Allow full screen notifications" || true
  sleep 2
  # A settings page (not a dialog) stays open after the switch: go back to the app (its tab bar).
  on_screen "Записи" || { adb shell input keyevent KEYCODE_BACK; sleep 2; }
done
sleep 2
shot 00c-setup-done
SETUP_LEFT=$(on_screen "Чтобы всё работало само" && echo "card still shown" || echo "card gone")
log "first launch: $SETUP_STEPS setup steps, then $SETUP_LEFT"

# Whatever the walk-through missed, allow it now so the rest of the demo does not depend on it.
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG USE_FULL_SCREEN_INTENT allow || true
adb shell dumpsys deviceidle whitelist +$PKG >> "$LOG"

log "copy the demo night into the app"
adb push demo/night.wav /data/local/tmp/night.wav > /dev/null
adb shell "cat /data/local/tmp/night.wav | run-as $PKG sh -c 'mkdir -p files && cat > files/night.wav'"
adb shell run-as $PKG ls -l files >> "$LOG" 2>&1

# ---------- Part 1: a night of sleep talk ----------
app
sleep 5
shot 01-home

adb shell screenrecord --time-limit 180 --bit-rate 3000000 /sdcard/demo.mp4 &
REC=$!
sleep 2
log "start recording the demo night"
app --es demo_wav night.wav
sleep 4
shot 02-listening
sleep 14
shot 03-first-phrase
sleep 50
shot 03b-snoring
adb shell cmd statusbar expand-notifications
sleep 3
shot 04-notification
adb shell cmd statusbar collapse

for _ in $(seq 1 120); do service_running || break; sleep 2; done
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
CLIP_NAMES=$(adb shell "run-as $PKG sh -c 'ls files/nights/*/*.wav'" 2>/dev/null)
CLIPS=$(echo "$CLIP_NAMES" | grep -c '__speech')
# Only the voice is kept by default: snoring is counted by the minute, not saved as clips.
OTHERS=$(echo "$CLIP_NAMES" | grep '\.wav' | grep -vc '__speech')
log "clips kept from the demo night: $CLIPS phrases (expected 3), $OTHERS other sounds (expected 0)"
echo "$CLIP_NAMES" >> "$LOG"

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
# ---------- Part 3: the alarm rings over the lock screen ----------
log "set the alarm one minute ahead, lock the phone"
app --ez demo_stop true
sleep 3
app --ei demo_alarm_in 2
sleep 3
adb shell input keyevent KEYCODE_SLEEP
RANG=""
for i in $(seq 1 100); do
  if adb shell dumpsys activity activities | grep -q "sonnik.app.AlarmActivity"; then RANG="after ~$((i * 2)) s"; break; fi
  sleep 2
done
log "alarm: ${RANG:-DID NOT RING}"
sleep 2
shot 13-alarm
tap_on "Выключить"
sleep 3
shot 14-after-alarm

# ---------- Part 4: the dream journal ----------
log "write a dream down (typed in for the demo)"
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell am start -n "$PKG/sonnik.app.MainActivity" --ez new_dream true \
  --es demo_dream_text "'Мне снилось, что я опаздываю на поезд, а вокзал превращается в мою старую школу. Я ищу нужный класс, но все двери заперты.'" > /dev/null
sleep 3
shot 15-dream
tap_on "Готово"
sleep 2
shot 16-dreams
tap_on "Сны"
sleep 1
on_screen "опаздываю на поезд" contains && DREAM_SAVED=yes || DREAM_SAVED=""
log "dream in the journal: ${DREAM_SAVED:-NO}"

# ---------- Part 5: large text, a small phone, a setting changing mid-dream ----------
log "large text (font scale 1.3)"
adb shell settings put system font_scale 1.3
sleep 3
app --ez records false
sleep 3
shot 17-big-night
scroll_down
shot 18-big-night-lower
app --ez records true
sleep 3
shot 19-big-records
tap_on "Сны"
sleep 2
shot 20-big-dreams
tap_on "опаздываю на поезд" contains
sleep 2
shot 21-big-dream
scroll_down
shot 22-big-dream-lower
adb shell input keyevent KEYCODE_BACK
sleep 2

log "small phone: 720x1280 at 320 dpi (360x640 dp), large text"
adb shell wm size 720x1280
adb shell wm density 320
sleep 4
app --ez records false
sleep 3
shot 23-small-night
scroll_down 1000 300
shot 23b-small-night-lower

log "the morning on the small phone: the alarm, then the dream screen"
adb shell dumpsys activity activities | grep -q "sonnik.app.AlarmActivity" && log "the first alarm screen is still open"
app --ei demo_alarm_in 2
sleep 3
adb shell input keyevent KEYCODE_SLEEP
RANG2=""
for i in $(seq 1 100); do
  if adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | grep -q "AlarmActivity"; then
    RANG2="after ~$((i * 2)) s"; break
  fi
  sleep 2
done
log "second alarm: ${RANG2:-DID NOT RING}"
sleep 2
shot 24-small-alarm
tap_on "Выключить"
sleep 2
adb shell wm dismiss-keyguard
sleep 3
shot 25-small-morning-dream
adb shell input keyevent KEYCODE_BACK
sleep 2

# Android rebuilds the screen when a phone setting changes: text size, display size, or the
# light/dark theme switching by itself at sunrise, which is just when dreams get written down.
log "a phone setting changes while a dream is being written"
app --ez new_dream true --es demo_dream_text "'Мне снился кот, который читал газету на крыше.'"
sleep 3
shot 26-dream-before-setting-change
adb shell settings put system font_scale 1.0
sleep 4
shot 27-dream-after-setting-change
if on_screen "читал газету" contains; then
  MIDDREAM="editor still open"
  tap_on "Готово"
else
  MIDDREAM="editor closed"
fi
sleep 2
tap_on "Сны" || true
sleep 2
on_screen "читал газету" contains && MIDDREAM="$MIDDREAM, dream saved" || MIDDREAM="$MIDDREAM, dream LOST"
shot 28-journal-after-setting-change
log "dream being written when a setting changed: $MIDDREAM"

adb shell wm size reset
adb shell wm density reset
adb shell settings put system font_scale 1.0

adb shell "run-as $PKG cat files/events.log" > "$OUT/events.log" 2>/dev/null
adb logcat -d > "$OUT/logcat.txt" 2>&1
grep -E "Sonnik|FullScreen|FSI|$PKG" "$OUT/logcat.txt" > "$OUT/logcat-app.txt"
log "done"

FAILED=0
[ "$CLIPS" = 3 ] || { log "FAIL: expected 3 phrases, got $CLIPS"; FAILED=1; }
[ "$OTHERS" = 0 ] || { log "FAIL: sounds other than speech were saved although only the voice is asked for"; FAILED=1; }
[ -n "$STARTED" ] || { log "FAIL: recording did not start by itself"; FAILED=1; }
[ -n "$RANG" ] || { log "FAIL: the alarm did not ring"; FAILED=1; }
[ -n "$DREAM_SAVED" ] || { log "FAIL: the dream was not saved"; FAILED=1; }
case "$MIDDREAM" in *"dream saved") ;; *) log "FAIL: a dream being written was lost when a phone setting changed"; FAILED=1;; esac
log "first launch: $SETUP_STEPS setup steps, $SETUP_LEFT"
exit $FAILED
