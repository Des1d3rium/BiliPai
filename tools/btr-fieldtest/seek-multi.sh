#!/bin/bash
# seek-multi.sh <label> <BV>...: one app process, cold cache, each video opened then seeked twice.
S=$(cd "$(dirname "$0")" && pwd); P=${PKG:-com.android.purebilibili.debug}; label=$1; shift
front() { adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q "$P/"; }
adb shell am force-stop $P; adb shell run-as $P rm -rf cache/playback_media_cache
adb shell monkey -p $P -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
adb logcat -c; adb logcat -v time BTR:I '*:S' > $S/multi-$label.log 2>&1 & LP=$!
for bv in "$@"; do
  adb shell am start -a android.intent.action.VIEW -d "https://www.bilibili.com/video/$bv" $P >/dev/null; sleep 8
  for i in 1 2; do
    f=$(python3 -I -c "import random; print(random.randint(10,90))")
    if front; then adb shell input tap 720 560; sleep 0.5; adb shell input tap $((79 + 1287*f/100)) 729; echo "$bv seek $f%"
    else echo "$bv seek skipped (app not in front)"; fi
    sleep 20
  done
done
kill $LP 2>/dev/null; wait $LP 2>/dev/null
python3 -I - $S/multi-$label.log <<'PY'
import re,sys
lines=open(sys.argv[1],encoding='utf-8',errors='ignore').read().splitlines()
body=lambda l: l.split('): ',1)[1] if '): ' in l else ''
kinds={'btr':0,'host':0}
for l in lines:
    b=body(l)
    for k in kinds:
        if b.startswith(k+' '): kinds[k]+=int(dict(re.findall(r'(\w+)=([^ ]+)',b))['bytes'])
print('  data:', {k: f"{v/1048576:.1f} MiB" for k,v in kinds.items() if v})
starts=[]; prev=None
for l in lines:
    b=body(l)
    if not b.startswith('buf '): continue
    m=dict(re.findall(r'(\w+)=([^ ]+)',b)); t=float(m['t'].rstrip('s'))
    if prev is None or t < prev: starts.append([])
    starts[-1].append((t,m)); prev=t
res=[next((t for t,m in s if m['state']=='3' and m['playing']=='true'),None) for s in starts]
print('  resume after each start (s):', [round(r,2) if r is not None else None for r in res])
for b in [body(l) for l in lines if re.match(r'(btr failed|ban )',body(l))][:6]: print('  ',b)
PY
