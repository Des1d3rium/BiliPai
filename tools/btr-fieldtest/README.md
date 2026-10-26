# BTR field tests (adb)

Scripts used to measure 线程撕裂者 on a real phone. They drive the app over adb and read the
`BTR` logcat tag (one line per range, host throughput, bans, thread changes, and a buffer trace
for 90 s after each open or seek). Logs and screenshots stay in this directory and are ignored.

| Script | What it does |
| --- | --- |
| `ui.sh <text>` | Coordinates of the first on-screen element containing `<text>` (uiautomator dump). |
| `setcfg.sh A\|B\|C ["大陆 CDN"\|"海外 CDN"]` | A: plugin off. B: plugin on, 多线程传输 off, 抗波动缓冲 on. C: everything on. Reads every switch back and stops if it does not match. |
| `seek-multi.sh <label> <BV>...` | Cold cache, one app process, opens each video and seeks to two random points; writes `multi-<label>.log`. |
| `seek-run.sh <label> <BV> [seeks] [secs]` | One video, force-stopped app per run; writes `seek-<label>.log`. |
| `batch3.sh`, `batch-mode.sh` | The A/B/C and mainland/overseas comparisons, three rounds each, in turn. |
| `analyze.py LABEL=glob ...` | Resume times, stalls during playback, data, BTR window and aggregate throughput, nodes. |

```bash
PKG=com.android.purebilibili.debug ./setcfg.sh C "海外 CDN"
PKG=com.android.purebilibili.debug ./seek-multi.sh run1 BV1KinU6vECs BV1DkpU67E49
python3 analyze.py C='multi-run1*.log'
```

Notes:

- `PKG` defaults to the debug app. Clearing the playback cache uses `run-as`, which works only on
  the debuggable build; on the smooth/dev builds the cache is not cleared.
- Taps on the progress bar (`x = 79 + 1287 * f/100`, `y = 729`) are calibrated for a
  1440 × 3168 screen (OnePlus CPH2653) in portrait. Recalibrate on another phone: show the
  player controls and read the slider bounds with `ui.sh` or a uiautomator dump.
- Keep the phone unlocked (developer option "stay awake while charging"); every tap first checks
  that the app is in front and stops otherwise.
- The apps show their own terms page and need a login on first start; do those by hand.
