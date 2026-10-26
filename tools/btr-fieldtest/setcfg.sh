#!/bin/bash
# setcfg.sh A|B|C — A: plugin off; B: plugin on, transfer off, buffer on; C: plugin on, transfer on, buffer on.
S=$(cd "$(dirname "$0")" && pwd); P=${PKG:-com.android.purebilibili.debug}; cfg=$1
front() { adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q "$P/"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb shell cat /sdcard/ui.xml > $S/ui.xml; }
# switch_state <label>: prints "x y on|off" for the switch nearest below/at the label row.
switch_state() { dump; python3 -I - $S/ui.xml "$1" <<'PY'
import re,sys
x=open(sys.argv[1],encoding='utf-8').read(); nodes=re.findall(r'<node [^>]*>',x); label=sys.argv[2]
box=lambda n: list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]*)"',n).group(1))))
ls=[box(n) for n in nodes if re.search(r' text="'+re.escape(label)+'"',n)]
if not ls: sys.exit(0)
y=ls[0][1]; sws=[(box(n),'checked="true"' in n) for n in nodes if 'checkable="true"' in n and box(n)[3] > y-40]
if not sws: sys.exit(0)
b,c=min(sws,key=lambda s: s[0][1]); print((b[0]+b[2])//2,(b[1]+b[3])//2,'on' if c else 'off')
PY
}
set_switch() {  # set_switch <label> on|off
  local st; st=$(switch_state "$1"); [ -z "$st" ] && { echo "no switch for $1" >&2; exit 1; }
  set -- "$1" "$2" $st
  if [ "$5" != "$2" ]; then front || exit 1; adb shell input tap $3 $4; sleep 1.5; fi
  st=$(switch_state "$1"); [ "${st##* }" = "$2" ] || { echo "$1 is ${st##* }, wanted $2" >&2; exit 1; }
}
adb shell am force-stop $P; adb shell monkey -p $P -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
for i in 1 2 3 4; do xy=$($S/ui.sh "设置" | cut -d'|' -f1 | xargs); [ -n "$xy" ] && { adb shell input tap $xy; break; }; front || exit 1; adb shell input keyevent KEYCODE_BACK; sleep 1; done; sleep 2
xy=$($S/ui.sh "插件中心" | cut -d'|' -f1 | xargs); [ -z "$xy" ] && { echo "插件中心 not found" >&2; exit 1; }; adb shell input tap $xy; sleep 2
for i in 1 2 3 4 5; do [ -n "$($S/ui.sh 线程撕裂者)" ] && break; front || exit 1; adb shell input swipe 720 2400 720 1100 400; sleep 1; done
want=$([ "$cfg" = A ] && echo off || echo on)
set_switch "线程撕裂者" $want
if [ "$cfg" != A ]; then
  xy=$($S/ui.sh "线程撕裂者" | cut -d'|' -f1 | xargs); front && adb shell input tap $xy; sleep 2
  set_switch "多线程传输" $([ "$cfg" = C ] && echo on || echo off)
  if [ -n "$2" ]; then   # optional CDN mode chip: 大陆 CDN | 海外 CDN
    xy=$($S/ui.sh "$2" | cut -d'|' -f1 | xargs); [ -z "$xy" ] && { echo "chip $2 not found" >&2; exit 1; }
    front || exit 1; adb shell input tap $xy; sleep 1.5
    dump; python3 -I - $S/ui.xml "$2" <<'PY' || { echo "chip $2 not selected" >&2; exit 1; }
import re,sys
x=open(sys.argv[1],encoding='utf-8').read(); nodes=re.findall(r'<node [^>]*>',x)
box=lambda n: list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]*)"',n).group(1))))
t=[box(n) for n in nodes if re.search(r' text="'+re.escape(sys.argv[2])+'"',n)][0]
# the chip is the smallest selectable/checkable node that contains the label
cands=[(n,box(n)) for n in nodes if ('selected="true"' in n or 'checked="true"' in n)]
ok=any(b[0]<=t[0] and b[1]<=t[1] and b[2]>=t[2] and b[3]>=t[3] for n,b in cands)
sys.exit(0 if ok else 1)
PY
  fi
  for i in 1 2 3; do [ -n "$(switch_state 抗波动缓冲)" ] && break; front || exit 1; adb shell input swipe 720 2400 720 1500 400; sleep 1; done
  set_switch "抗波动缓冲" on
fi
echo "config $cfg set"
