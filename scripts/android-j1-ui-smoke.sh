#!/usr/bin/env bash
set -euo pipefail

package='com.appfusion.product'
activity="$package/.MainActivity"
adb_bin="${ADB:-${ANDROID_HOME:?ANDROID_HOME must be set}/platform-tools/adb}"
apk_path="${APPFUSION_ANDROID_APK:-androidApp/build/outputs/apk/debug/androidApp-debug.apk}"
evidence_dir="${APPFUSION_J1_EVIDENCE_DIR:-build/android-j1-evidence}"
remote_xml='/sdcard/appfusion-window.xml'
local_xml="$evidence_dir/window.xml"
title='J1EncryptedNote42'
body='PrivateBodyAlpha42'

mkdir -p "$evidence_dir"
test -x "$adb_bin"
test -f "$apk_path"

dump_ui() {
  for _ in $(seq 1 5); do
    if "$adb_bin" shell uiautomator dump "$remote_xml" >/dev/null 2>&1 && \
       "$adb_bin" pull "$remote_xml" "$local_xml" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo 'Unable to capture the Android UI hierarchy.' >&2
  return 1
}

node_center() {
  local mode="$1"
  local needle="$2"
  python3 - "$local_xml" "$mode" "$needle" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, mode, needle = sys.argv[1:]
root = ET.parse(path).getroot()
for node in root.iter('node'):
    value = node.attrib.get('resource-id', '') if mode == 'id' else node.attrib.get('text', '')
    matches = value == needle if mode == 'id' else needle in value
    if not matches or node.attrib.get('enabled') == 'false':
        continue
    points = [int(value) for value in re.findall(r'\d+', node.attrib.get('bounds', ''))]
    if len(points) == 4:
        print(f'{(points[0] + points[2]) // 2} {(points[1] + points[3]) // 2}')
        raise SystemExit(0)
raise SystemExit(1)
PY
}

tap_node() {
  local mode="$1"
  local needle="$2"
  local allow_scroll="${3:-false}"
  local center=''
  for _ in $(seq 1 12); do
    dump_ui
    if center="$(node_center "$mode" "$needle")"; then
      read -r x y <<<"$center"
      "$adb_bin" shell input tap "$x" "$y"
      return 0
    fi
    if [ "$allow_scroll" = 'true' ]; then
      "$adb_bin" shell input swipe 240 700 240 220 350
    fi
    sleep 1
  done
  echo "Unable to find UI $mode '$needle'." >&2
  cat "$local_xml" >&2 || true
  return 1
}

wait_for_text() {
  local needle="$1"
  local allow_scroll="${2:-false}"
  for _ in $(seq 1 60); do
    dump_ui
    if node_center text "$needle" >/dev/null; then
      return 0
    fi
    if [ "$allow_scroll" = 'true' ]; then
      "$adb_bin" shell input swipe 240 700 240 220 300
    fi
    sleep 1
  done
  echo "Timed out waiting for UI text '$needle'." >&2
  cat "$local_xml" >&2 || true
  return 1
}

start_app() {
  "$adb_bin" shell am start -W -n "$activity" | tee -a "$evidence_dir/activity-start.txt"
  wait_for_text 'AppFusion'
  sleep 2
}

capture_evidence() {
  dump_ui || true
  "$adb_bin" exec-out screencap -p >"$evidence_dir/j1-opened-document.png" || true
  "$adb_bin" logcat -d >"$evidence_dir/logcat.txt" || true
}

trap capture_evidence EXIT

"$adb_bin" install -r -t "$apk_path" | tee "$evidence_dir/install.txt"
"$adb_bin" shell pm clear "$package" | tee "$evidence_dir/clear.txt"
start_app

tap_node id "$package:id/document_title"
"$adb_bin" shell input text "$title"
tap_node id "$package:id/document_body"
"$adb_bin" shell input text "$body"
"$adb_bin" shell input keyevent 111
tap_node id "$package:id/save_document" true
wait_for_text "$title" true

"$adb_bin" shell am force-stop "$package"
if "$adb_bin" shell pidof "$package" | grep -q .; then
  echo 'The application process survived force-stop.' >&2
  exit 1
fi
start_app

tap_node id "$package:id/search_query" true
"$adb_bin" shell input text "$title"
"$adb_bin" shell input keyevent 111
tap_node id "$package:id/search_documents" true
wait_for_text "$title" true
tap_node id "$package:id/search_result_item" true
wait_for_text "$body"

printf '%s\n' \
  'APPFUSION_ANDROID_J1=PASS' \
  "title=$title" \
  'operations=create,force-stop,relaunch,search,decrypt,reopen' \
  >"$evidence_dir/result.txt"
cat "$evidence_dir/result.txt"
