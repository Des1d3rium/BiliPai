<!--
草稿：尚未提交。
提交方式：从 jay3-yy/BiliPai 的 main（v0.6.2，ece28c773）拉出分支 fix/first-play-stall，
只含 1 个提交（本地已准备好：39dfb5a40），推到你自己的 fork 后向 jay3-yy/BiliPai:main 发起 PR。
下面 "---" 之后是 PR 正文。
-->

**标题：** `fix(player): 修复首次打开视频时播放器停在封面/加载中不播放`

---

## 问题

真机（OnePlus CPH2653，ColorOS / Android 16）上，0.6.1 与 0.6.2 都能复现两种现象：

1. **冷启动后第一次打开视频**：画面停在封面或"加载中"，不出画面；退出视频页再进入就能正常播放。
2. **从播放条 / 后台听视频回到视频页**：一直停在加载骨架屏，没有错误提示；再进一次就正常。可能就是 #1012 第 4 点描述的现象。

## 根因

**1. 起播缓冲期间媒体通知被移除，系统媒体面板随后对会话发出 `stop`**

`MiniPlayerManager.updateMediaMetadata()` 异步加载封面，加载完成后调用 `pushNotification()`。冷启动时这一刻播放器往往还在等首帧（`playWhenReady = true`，`STATE_BUFFERING`），`isPlaying` 为 `false`，于是 `shouldKeepPlaybackNotificationVisible()` 返回 `false`，代码取消了媒体通知并停止 `PlaybackService`。

在 ColorOS 上，媒体通知被移除后，系统界面会立即对这个 MediaSession 发出 `stop`，播放器进入 `STATE_IDLE`，之后没有逻辑会再次 `prepare`。日志的先后顺序：

```
notification_canceled: [0|com.android.purebilibili|1002|...,8 (REASON_APP_CANCEL),...]
MSessionService: Terminating service that was started by a stale start intent
MediaSessionService: tempAllowlistTargetPkgIfPossible callingPackage:com.android.systemui ... reason:MediaSessionRecord:stop
DanmakuManager: onPlaybackStateChanged: state=1        ← 2 ms 后进入 IDLE
```

第二次进入时网络、封面都已就绪，播放器在封面加载完之前就已经 `isPlaying`，所以不会触发。

**2. 后台音频收回超时被当作"协程取消"静默吞掉**

`VideoPlayerState` 进入视频时先 `await miniPlayerManager.reclaimRemoteAudioForVideo()`，之后才 `loadVideo()`。`BackgroundAudioConnection` 的 `recover()` / `reclaim()` / `transfer()` 都用 `withTimeout`，超时抛出的 `TimeoutCancellationException` 是 `CancellationException` 的子类。调用点的 `catch (e: CancellationException) { throw e }` 把它当成协程取消重新抛出，`LaunchedEffect` 静默结束：`loadVideo()` 没有执行，也不会走到"后台音频暂未交回"的提示分支。

## 修复

- `isPlayerStartingPlayback()`：`playWhenReady && STATE_BUFFERING` 视为"正在起播"，`pushNotification()` 在这种情况下保留通知。`shouldKeepPlaybackNotificationVisible()` 新增默认为 `false` 的参数，原有调用和测试不受影响。
- `withBackgroundAudioTimeout()`：把后台音频的超时转换为普通异常 `BackgroundAudioTimeoutException`；调用方自身被取消时仍然是 `CancellationException`。
- `reclaimRemoteAudioForVideo()`：后台音频进程失败或超时时，停止该服务（同时停止它的播放，不会出现两路声音），打一条 W 日志，然后继续加载视频。

改动 2 个文件（+50 / −14），另加测试。

## 验证

- 新增单元测试：`BackgroundAudioTimeoutTest`（3 个：超时变成普通异常、正常返回、调用方取消仍是取消），`BackgroundPlaybackPolicyTest` 新增 2 个（起播缓冲时保留通知、只有 `playWhenReady` 且缓冲中才算起播）。
- 基于 v0.6.2 本地运行 `:app:testDebugUnitTest --tests 'com.android.purebilibili.feature.audio.player.*' --tests '...BackgroundPlaybackPolicyTest'`：88 个全部通过。
- 真机，原因 1：修复前，冷启动后立即打开视频 3 次中 2 次进入 IDLE（日志如上）；修复后近 30 次冷启动打开视频，未再出现进入 IDLE 后停住的情况。（这些测试是在我自己的分支上做的，那个分支还包含其他与本 PR 无关的改动。）
- 原因 2 是通过日志和代码分析定位的，修复由上面的单元测试覆盖，没有专门在真机上复现"后台听视频 → 回到视频页"这个场景。
