#!/bin/bash
# batch-mode.sh: mainland and overseas (both automatic threads, anti-jitter on) in turn, three rounds; ends on overseas.
S=$(cd "$(dirname "$0")" && pwd)
for round in 1 2 3; do
  for mode in 大陆 海外; do
    tag=$([ $mode = 大陆 ] && echo M || echo O)
    if ! { [ $round = 1 ] && [ $tag = M ]; }; then $S/setcfg.sh C "$mode CDN" || { echo "set $mode failed"; exit 1; }; fi
    echo "== round $round $mode"; $S/seek-multi.sh mode-$tag$round BV1KinU6vECs BV1DkpU67E49 2>&1 | grep -v Terminated
  done
done
