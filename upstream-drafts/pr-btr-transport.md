<!--
草稿：尚未提交。建议先在 #792 留言（comment-792-btr.md），等维护者表态后再按意见调整并提交。
代码：本地分支 feat/btr-transport，基于 jay3-yy/BiliPai main（v0.6.2，ece28c773），1 个提交（1a1bd7900），
28 个文件 +3473/−2，包含 btr-transport/LICENSE-BTR.txt（MIT 原文）。
下面 "---" 之后是 PR 正文。
-->

**标题：** `feat(plugin): 线程撕裂者 — 点播多节点 Range 并发下载（移植 Bilibili-thread-ripper）`

---

关联 #792（#793 已关闭）。

## 背景

海外看 B 站时，单条连接经常只有几 Mbps；节点一抖动，画面就卡住。现有的 CDN 智能选线只能在 B 站给的 2–3 个地址里挑一个，下载仍然是单连接。[Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper)（MIT）在浏览器里用"多节点 + 分块并发 + 对冲补发"解决了这个问题。这个 PR 把它的下载器移植到 BiliPai 的 Media3 播放链路上。

## 做了什么

新增模块 `:btr-transport`（纯 Kotlin + OkHttp + Media3 DataSource）和内置插件「线程撕裂者」，**默认关闭**。

对照原版 `src/range-core.js`、`src/cdn-resolver.js`、`src/idm-downloader.js`（commit bbf4d3d）逐个函数移植：

- **节点**：大陆 8 个 / 海外 4 个 / 自定义镜像，把同一个签名地址换到这些主机上；节点和地址分别计数停用（同一节点连续两次空返回即停用，10 分钟后自动恢复，跨视频保留）；按节点测速（跨视频、跨重启保留 1 小时）。
- **调度**：按类别（探测 > 起播 > 普通）、优先级、播放截止时间排序的连接队列，并随等待时间逐步提升优先级；按速度做平滑加权轮转来分配分片，没测过的节点给一两块试速。
- **补救**：按截止时间判断是否对冲补发；首字节 5.5 s、传输中停顿 4 s、单次 15 s 三种超时；从已校验的字节处续传；遇到 412/429 时退避。
- **自动线程数**：8 → 12 → 16 → 24 → 32 分档；升档后观察 10 s，没有变快就回退并暂停该档；每次起播先用 16 线程。

在 BiliPai 上额外做的：

- **默认整文件流式读取也能走 BTR**：按窗口切分（先 1 MiB，之后 2 MiB），播放器读到离窗口不足一个窗口时才开始下载那个窗口，所以播放器缓冲满了，下载也会停。
- **签名过期前 3 分钟自动刷新**：通过 `VideoRepository.getPlayUrlData` 重新获取地址，换掉正在使用的签名，不重新加载播放器；节点返回 403 时也会提前刷新。
- **抗波动缓冲（可选）**：加深播放器缓冲，前 1/4 的前向上限从 2 s 提到 15 s，最小缓冲 20 s。
- **失败回退**：BTR 任何一步失败，都从第一个未交付的字节开始，交回宿主原有的 DataSource（`CdnPlaybackDataSource`）继续读，播放不受影响。

### 对现有文件的改动（共 8 处挂钩，其余都是新文件）

| 文件 | 改动 |
|---|---|
| `settings.gradle.kts`、`app/build.gradle.kts` | 引入 `:btr-transport` |
| `PureApplication.kt` | 注册 `BtrPlugin` |
| `PlaybackMediaCache.kt` | 用 `BtrDataSource(host)` 包装上游 DataSource |
| `VideoPlaybackViewModel.kt` | 注册音视频轨道；`attachPlayer` 里挂上播放监视器和地址刷新回调 |
| `PortraitVideoLoadPolicy.kt`、`BangumiPlayerViewModel.kt` | 注册音视频轨道 |
| `VideoPlayerState.kt` | 开启抗波动缓冲时加深缓冲策略（只增不减） |

### 隐私

- 签名地址不写日志、不持久化。
- 日志（tag `BTR`，Info 级别）只记录主机名、字节数、耗时和缓冲秒数。
- 测速表（`filesDir/btr_node_stats.tsv`）只存"主机名 → 速度、时间"。

## 验证

- `:btr-transport:testDebugUnitTest`：40 个 JVM 单元测试，全部通过。用虚拟时间覆盖：顺序重组、卡住的节点被切走、断点续传、拒绝的节点被停用、镜像返回的文件长度不一致时报错、按截止时间对冲、起播探测、init/index 竞速、签名刷新、停用名单到期恢复、测速持久化等。
- 基于 v0.6.2 编译 `:app:assembleDebug` 通过。<!-- TODO（提交前）：本分支单独跑 :btr-transport:testDebugUnitTest 和 :app:assembleDebug；:app:assembleDev（R8）编完后在这里补上结果。 -->
- 以上测试和实测都是在我自己的分支上做的（v0.6.2 + 本 PR + 一个无关的首播修复）。
- 真机 OnePlus CPH2653（Android 16），美国 Wi-Fi，两个 1080P 视频，每轮清缓存，在同一进程里依次打开两个视频，各随机跳转两次，配置轮流测 3 轮：

  | | 默认 | 仅抗波动缓冲 | BTR 全开（海外 + 自动） |
  |---|---|---|---|
  | 播放途中卡顿 | 6 次，共 14.2 s | 1 次，6.5 s | 0 次 |
  | 打开或跳转后恢复播放（平均 / 最长） | 1.51 / 5.50 s | 1.53 / 7.58 s | 1.41 / 3.39 s |
  | 每轮下载量 | 45.7 MiB | 64.5 MiB | 108.6 MiB |

  大陆 vs 海外（都是自动线程数）：总吞吐 24 vs 107 Mbps，恢复播放平均 2.35 vs 1.34 s。签名强制刷新：音视频两条轨道在 270 ms 内换好，播放和后续 36 个区间都正常。

## 已知限制

- **流量**：比默认多 1.4–2.4 倍，来源是测速探测、补发副本、抗波动缓冲。
- **网络稳定时没有收益**：在网络稳定的时段，BTR 在冷门内容回源时还多卡过两次（各 1–2 s）。它的价值主要在节点抖动时。
- **截止时间是估算的**：播放器不告诉 DataSource 某个字节对应视频的第几秒，所以截止时间只能按"当前缓冲时长"估算，不如原版按分段时间精确。
- **覆盖范围**：直播不在范围内；签名刷新目前只接在普通视频页（番剧、竖屏信息流只注册了轨道）。
- **测试范围**：只在一台设备上实测过。

## 许可

BTR 为 MIT 许可，原文在 `btr-transport/LICENSE-BTR.txt`，`THIRD_PARTY_NOTICES.md` 已补充说明；BiliPai 为 GPL-3.0，两者兼容。这个移植不是 BTR 原作者的官方发布。
