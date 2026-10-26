# 交接说明（2026-10-25）

换电脑或开新会话时，先读本文件和 `FORK.md`。前者是进度和结论，后者是挂钩位置和同步流程。

## 仓库与分支

| 分支 | 内容 | 远程 |
| --- | --- | --- |
| `bilipai-plus` | 日常使用的 fork：上游 v0.6.2 + 首播修复 + 线程撕裂者 + 文档、工具 | `origin`（Des1d3rium/BiliPai） |
| `feat/btr-transport` | 只含线程撕裂者的 PR 分支，基于上游 main（v0.6.2），1 个提交，**尚未提交 PR** | `origin` |
| `fix/first-play-stall` | 首播修复，已提交为上游 PR #1021 | `origin` |
| `bilipai-plus-before-v0.6.2` | 合并 v0.6.2 之前的备份，只在原来那台 Mac 上 | 未推送 |

远程：`upstream` = jay3-yy/BiliPai，`origin` = Des1d3rium/BiliPai。fork 的 `main` 不使用，保持和上游一致。

新电脑上：

```bash
git clone -b bilipai-plus https://github.com/Des1d3rium/BiliPai.git BiliPai-fork
cd BiliPai-fork
git remote add upstream https://github.com/jay3-yy/BiliPai.git
git fetch upstream --tags
git fetch origin feat/btr-transport:feat/btr-transport fix/first-play-stall:fix/first-play-stall
```

## 已提交到上游

- PR [#1021](https://github.com/jay3-yy/BiliPai/pull/1021)：首播失败修复。等待维护者处理；CI 需要维护者批准才会运行。
- Issue [#1022](https://github.com/jay3-yy/BiliPai/issues/1022)：抗波动缓冲功能请求。

`upstream-drafts/` 里是这两项的草稿，以及另外两份**尚未发出**的草稿：

- `comment-792-btr.md`：给 #792 的回复。建议先发这个，等维护者对线程撕裂者表态后再提 PR。
- `pr-btr-transport.md`：线程撕裂者 PR 正文。其中「验证」一节有 TODO。

## 待办

1. 发出 #792 回复（草稿已就绪，需本人确认）。
2. 在 `feat/btr-transport` 上单独跑 `:btr-transport:testDebugUnitTest` 和 `:app:assembleDebug`，确认它不依赖首播修复，然后补全 PR 草稿里的 TODO。
3. 带 R8 的 `:app:assembleDev`。在 8 GB 的 Mac 上，R8 这一步跑了 100 多分钟仍未完成；新电脑最好有 16 GB 以上内存。
4. 可选改进：
   - 番剧、竖屏信息流也接上签名刷新（目前只有普通视频页）；
   - 节点测速按网络类型（Wi-Fi / 移动网络）分开存；
   - 打开分段加载、拿到 sidx 分段时间后，按分段计算精确截止时间（这是和 BTR 原版差距最大的地方）；
   - 直播不在范围内。
5. 维护者回复 PR #1021 或 issue #1022 时跟进（没有开启 Auto-fix）。

## 手机与安装

- 设备：OnePlus CPH2653（ColorOS / Android 16），美国，Wi-Fi。
- 官方正式版 `com.android.purebilibili` 一直未改动。
- `com.android.purebilibili.debug`：debug 构建，测试用（可以 `run-as` 清缓存），界面略卡。
- `com.android.purebilibili.dev`：smooth 构建，0.6.2-smooth，不可调试、无 R8，日常使用。
- 两个测试版的配置都是：线程撕裂者开、多线程传输开、海外 CDN、自动线程数、抗波动缓冲开、仅 Wi-Fi。CDN 智能选线建议保持关闭。

## 关键结论

**首播失败的两个原因**（已在 PR #1021 修复）

1. 冷启动时，封面加载完成那一刻播放器还在缓冲，App 于是移除了媒体通知；ColorOS 的媒体面板随即对会话发出 `stop`，播放器停在 IDLE。
2. 后台音频收回超时抛出的 `TimeoutCancellationException` 被当作协程取消重新抛出，`loadVideo` 被静默跳过。

**上游代码里的几个事实**（v0.6.2）

- 「分段加载兼容模式」默认关闭，播放器以整文件流读取，长度未知。
- 视频前 1/4 只往前缓冲 2 秒（`earlyPlaybackMaxBufferMs`）。
- CDN 智能选线的并发下载（维护者 10-02 加入）只处理长度已知的请求，所以默认设置下不会生效；它只用 playurl 原样给出的签名地址，最多 3 路。
- 签名地址的有效期是 120 分钟。

**实测**（工具在 `tools/btr-fieldtest`；两个 1080P 视频 BV1KinU6vECs、BV1DkpU67E49；每种配置 3 轮轮流进行）

| | 默认 | 单连接 + 抗波动缓冲 | BTR 全开（海外 + 自动） |
| --- | --- | --- | --- |
| 播放途中卡顿 | 6 次，共 14.2 s | 1 次，6.5 s | 0 次 |
| 打开或跳转后恢复播放，最长 | 5.5 s | 7.6 s | 3.4 s |
| 每轮下载量 | 45.7 MiB | 64.5 MiB | 108.6 MiB |

- 海外 vs 大陆（都是自动线程数）：总吞吐 107 vs 24 Mbps，恢复播放平均 1.34 vs 2.35 s。和 BTR 原版默认推荐大陆节点的结论相反。
- 网络稳定的时段，默认配置也不卡；BTR 曾在冷门内容回源时多卡过两次，各 1–2 秒。BTR 的价值主要在节点抖动时。
- 不在乎流量，所以日常使用 C 配置（BTR 全开）。

## 新电脑的编译环境

1. 安装 JDK 21、Android SDK（platform `android-37.0`，build-tools `37.0.0`），在仓库根目录建 `local.properties`，写入 `sdk.dir=...`。
2. 准备 Miuix 本地源码：

   ```bash
   git clone https://github.com/compose-miuix-ui/miuix.git ../miuix
   git -C ../miuix checkout 153920c7
   git -C ../miuix apply "$PWD/tools/miuix-local-build.patch"   # AGP 9.3.2，并去掉 Gradle 9.5 不认识的两个 preview
   ```

3. 编译：

   ```bash
   ./gradlew -Pbili.miuix.source=../miuix :btr-transport:testDebugUnitTest :app:assembleDebug
   ./gradlew -Pbili.miuix.source=../miuix :app:assembleSmooth     # 日常用，无 R8，约 15 分钟（8 GB Mac）
   ./gradlew -Pbili.miuix.source=../miuix :app:assembleDev        # 带 R8，需要更多内存
   ```

4. 手机连上新电脑后，在手机上确认 USB 调试授权。

## 在新会话里继续

在仓库根目录打开 Claude Code，第一句话说：「先读 HANDOFF.md 和 FORK.md，然后继续待办」。
