#!/bin/bash
# ui.sh <text>: prints "x y | text" for the first on-screen element whose text or content-desc
# contains <text>, from a uiautomator dump. Prints nothing when it is not on screen.
DIR=$(cd "$(dirname "$0")" && pwd)
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml > "$DIR/ui.xml" 2>/dev/null || true
python3 -I - "$DIR/ui.xml" "$1" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
query = sys.argv[2]
for m in re.finditer(r'<node [^>]*>', xml):
    node = m.group(0)
    text = re.search(r' text="([^"]*)"', node).group(1)
    desc = re.search(r'content-desc="([^"]*)"', node).group(1)
    if query in text or query in desc:
        b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]*)"', node).group(1))))
        print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, '|', text or desc)
        break
PY
