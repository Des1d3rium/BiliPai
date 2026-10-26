#!/bin/bash
# seek-run.sh <label> <BV> [seeks=2] [secs=25]: cold cache, open, then random seeks; summarize.
S=$(cd "$(dirname "$0")" && pwd); P=com.android.purebilibili.debug
label=$1; bv=$2; seeks=${3:-2}; secs=${4:-25}
adb shell am force-stop $P
adb shell run-as $P rm -rf cache/playback_media_cache
adb shell monkey -p $P -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
adb logcat -c
adb logcat -v time BTR:I '*:S' > $S/seek-$label.log 2>&1 & LP=$!
adb shell am start -a android.intent.action.VIEW -d "https://www.bilibili.com/video/$bv" $P >/dev/null
sleep 8
fracs=""
for i in $(seq 1 $seeks); do
  f=$(python3 -I -c "import random; print(random.randint(10,90))"); fracs="$fracs $f%"
  echo "SEEK $f" >> $S/seek-$label.marks
  if adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q "$P/"; then
    adb shell input tap 720 560; sleep 0.5; adb shell input tap $((79 + 1287*f/100)) 729
  else echo "  (debug app not in front; seek skipped)"; fi
  sleep $secs
done
kill $LP 2>/dev/null; wait $LP 2>/dev/null
echo "[$label $bv] seeks:$fracs"
python3 -I - $S/seek-$label.log <<'PY'
import re,sys,collections
lines=open(sys.argv[1],encoding='utf-8',errors='ignore').read().splitlines()
def body(l): return l.split('): ',1)[1] if '): ' in l else ''
for kind in ('btr','host'):
    rows=[body(l) for l in lines if body(l).startswith(kind+' ')]
    b=t=0; hosts=collections.Counter(); per=[]
    for r in rows:
        m=dict(re.findall(r'(\w+)=([^ ]+)',r))
        bb=int(m['bytes']); tt=float(m.get('ms') or m.get('readMs')); b+=bb; t+=tt; per.append(bb*8/1000/max(tt,1))
        for h in (m.get('hosts') or m.get('node','')).split(','): hosts[h.split('.')[0]]+=1
    if rows:
        per.sort(); med=per[len(per)//2]
        print(f"  {kind}: {len(rows)} reads, {b/1048576:.1f} MiB, weighted {b*8/1000/max(t,1):.1f} Mbps, median {med:.1f} Mbps; nodes {dict(hosts.most_common(6))}")
# Time from each seek until playback resumed (state=3 playing=true), from the buffer trace.
traces=[body(l) for l in lines if body(l).startswith('buf ')]
starts=[]; prev=None
for r in traces:
    m=dict(re.findall(r'(\w+)=([^ ]+)',r)); t=float(m['t'].rstrip('s'))
    if prev is not None and t < prev: starts.append([])
    if not starts: starts.append([])
    starts[-1].append((t,m)); prev=t
for i,s in enumerate(starts):
    ready=next((t for t,m in s if m['state']=='3' and m['playing']=='true'),None)
    print(f"  start {i}: resumed after {ready if ready is not None else '>trace'} s")
for r in [body(l) for l in lines if re.match(r'(btr failed|ban )',body(l))][:6]: print('  ',r)
PY
rm -f $S/seek-$label.marks
