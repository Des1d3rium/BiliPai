#!/usr/bin/env python3
"""Summarize seek-multi.sh / seek-run.sh logs (logcat tag BTR).

Usage: analyze.py LABEL=glob [LABEL=glob ...]
  e.g. analyze.py A='multi-abc-A*.log' C='multi-abc-C*.log'

Per label: time to resume after each open or seek, stalls during playback, data per run,
aggregate BTR throughput (bytes over the time any window was downloading), window speeds
and the nodes used.
"""
import collections
import datetime
import glob
import re
import statistics as st
import sys


def body(line):
    return line.split('): ', 1)[1] if '): ' in line else ''


def fields(text):
    return dict(re.findall(r'(\w+)=([^ ]+)', text))


def stamp(line):
    return datetime.datetime.strptime(line[:18], '%m-%d %H:%M:%S.%f')


def parse(path):
    lines = open(path, encoding='utf-8', errors='ignore').read().splitlines()
    data, windows, speeds, nodes, starts, prev = 0, [], [], collections.Counter(), [], None
    for line in lines:
        b = body(line)
        if b.startswith(('btr ', 'host ')):
            m = fields(b)
            size = int(m['bytes'])
            data += size
            if b.startswith('btr '):
                ms = float(m['ms'])
                windows.append((stamp(line) - datetime.timedelta(milliseconds=ms), stamp(line), size))
                speeds.append(size * 8 / 1000 / max(ms, 1))
                for host in m['hosts'].split(','):
                    nodes[host.split('.')[0]] += 1
            else:
                nodes[m.get('node', '?').split('.')[0]] += 1
        if b.startswith('buf '):
            m = fields(b)
            t = float(m['t'].rstrip('s'))
            # The trace restarts at every open and seek.
            if prev is None or t < prev:
                starts.append([])
            starts[-1].append((t, m['state'], m['playing']))
            prev = t
    windows.sort()
    busy, current = 0.0, None
    for begin, end, _ in windows:
        if current and begin <= current[1]:
            current = (current[0], max(current[1], end))
        else:
            if current:
                busy += (current[1] - current[0]).total_seconds()
            current = (begin, end)
    if current:
        busy += (current[1] - current[0]).total_seconds()
    resumes, stalls, stall_s = [], 0, 0.0
    for start in starts:
        resumes.append(next((t for t, state, playing in start if state == '3' and playing == 'true'), None))
        seen, stalled_at = False, None
        for t, state, playing in start:
            if state == '3' and playing == 'true':
                seen = True
                if stalled_at is not None:
                    stall_s += t - stalled_at
                    stalled_at = None
            elif seen and state == '2' and stalled_at is None:
                stalls += 1
                stalled_at = t
    aggregate = sum(w[2] for w in windows) * 8 / 1e6 / busy if busy else None
    return data, resumes, stalls, stall_s, speeds, aggregate, nodes


def main():
    for spec in sys.argv[1:]:
        label, pattern = spec.split('=', 1)
        files = sorted(glob.glob(pattern))
        if not files:
            print(f'{label}: no files match {pattern}')
            continue
        data, resumes, missing, stalls, stall_s, speeds, aggregates, nodes = [], [], 0, 0, 0.0, [], [], collections.Counter()
        for path in files:
            d, r, s, ss, sp, ag, n = parse(path)
            data.append(d / 1048576)
            resumes += [x for x in r if x is not None]
            missing += sum(1 for x in r if x is None)
            stalls += s
            stall_s += ss
            speeds += sp
            if ag:
                aggregates.append(ag)
            nodes += n
        print(f'{label} ({len(files)} runs)')
        if resumes:
            print(f'  resume: mean {st.mean(resumes):.2f}s, median {st.median(resumes):.2f}s, max {max(resumes):.2f}s, '
                  f'>3s {sum(1 for x in resumes if x > 3)}/{len(resumes) + missing}, never started {missing}')
        print(f'  stalls during playback: {stalls} ({stall_s:.1f}s); data per run {st.mean(data):.1f} MiB')
        if speeds:
            print(f'  BTR windows: median {st.median(speeds):.1f} Mbps; aggregate {st.mean(aggregates):.1f} Mbps')
        print(f'  nodes: {dict(nodes.most_common(8))}')


if __name__ == '__main__':
    main()
