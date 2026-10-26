#!/bin/bash
# batch3.sh: A, B, C in turn, three rounds; ends on C.
S=$(cd "$(dirname "$0")" && pwd)
for round in 1 2 3; do
  for cfg in A B C; do
    $S/setcfg.sh $cfg || { echo "config $cfg failed"; exit 1; }
    echo "== round $round config $cfg"; $S/seek-multi.sh abc-$cfg$round BV1KinU6vECs BV1DkpU67E49 2>&1 | grep -v Terminated
  done
done
