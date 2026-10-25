# 视频起播、暂停与加载停滞排查

分析日期：2026-10-25。对比本地工作区与 `/Users/yiyang/Desktop/PiliPlus`（提交 `3d0aed958`），BiliPai 基线提交为 `89a0a9491`。这是源码分析，没有用户现场网络记录和首帧实测数据，不能据此断言全部 3–8 秒延迟来自同一原因。

## 两边的取流与网络流程

| 环节 | PiliPlus 本地源码 | BiliPai 修改前 | 本次处理 |
| --- | --- | --- | --- |
| 普通视频取流 | Web/WBI `playurl`，`fnval=4048`；正常清晰度发现请求 qn=129 | 同类 Web/WBI 接口和清晰度发现策略；已知 cid 时详情与取流并行，未知 cid 时先解析详情 | 保留账号、鉴权和取流接口，不改为移动端鉴权或游客回退 |
| WBI 签名密钥 | 本地持久缓存和进行中的共享请求 | 启动恢复共享缓存，但取流另用进程内私有缓存，首次取流可能重复查询 nav；私有查询最多尝试三次 | 取流复用共享持久缓存与刷新互斥锁 |
| 设备激活 | 账号初始化/刷新时激活，查询视频 URL 的方法本身没有激活等待 | 每次进入取流都先等待设备激活或其锁；首次网络异常可能拖住起播 | 激活等待上限 1 秒，超时使用当前账号原有 Cookie 继续；不更换账号或伪造权限 |
| 会员查询 | URL 返回的轨道参与清晰度选择 | 自动最高画质或高级默认画质且会员缓存为否时，先额外查一次播放账号 nav | 默认画质解析直接使用账号缓存；实际可用轨道仍由服务端取流响应决定 |
| 补齐画质 | 发现低于实际最高画质的缺失档位后，等待一次额外取流 | 同类串行补查，无独立的短超时 | 补查等待上限 1.5 秒，超时保留首次可播放结果；外层取消仍继续传播 |
| 缓冲 | mpv 缓冲参数按播放速度缩放 | Media3 起播门槛 Wi-Fi 700ms、移动网络 1000ms；恢复门槛 1400/2200ms，但视频前 1/4 下载上限固定 2000ms | 前段上限至少覆盖倍速折算后的起播和恢复门槛，并留 250ms 媒体时长余量 |
| CDN | 根据 URL 列表、节点设置和音频规则选址 | 已有相近选址规则、可选插件、多候选和自适应 DASH；重写线路有 2.5 秒保护，普通卡顿恢复要求已经出过首帧 | 普通固定画质线路首帧前连续 5 秒没有有效缓冲时尝试未试过的备用地址；自适应 DASH 仍保留原有机制 |
| 网络超时 | Dio API 连接及接收间隔均为 10 秒；媒体播放使用单独的 mpv 链路 | API 和媒体基于 OkHttp，连接 15 秒、读取间隔 20 秒；主加载器另有 15 秒保护 | 不把全局缩短超时作为性能修复；限制非必要等待，并修正加载准备阶段异常出口 |

网络客户端超时是失败上限，不是每次请求固定等待时长。不能把 Dio、mpv 和 OkHttp 的不同超时直接视为起播快慢的测量结果。

## 能从源码确认的故障条件

1. **前段缓冲可能停在不可恢复的位置。** 移动网络恢复需要 2.2 秒可播放时间，但固定 2 秒媒体缓冲上限可能阻止继续下载；倍速会进一步抬高所需媒体缓冲量。已核对本地 Media3 源码：`shouldStartPlayback` 会用播放速度把媒体缓冲时长换算为实际播放时长，且当前配置优先按时间门槛决定播放。
2. **会话恢复可能丢失自动继续意图。** 原逻辑在页面恢复活跃、但进场尚未完成时就清除挂起状态；进场完成也不是 effect 的 key。重复非活跃通知还可能用已经暂停的状态覆盖首次挂起前的播放意图。本次保留首次意图，并等待进场完成后恢复；原本手动暂停的播放器不会因此自动播放。
3. **加载准备失败可能没有错误页面。** 原来的会员检查和设置解析在主加载 try/catch 之外。本次移除首帧前的会员网络查询，并把准备阶段纳入已有异常处理。
4. **首帧前的普通线路缺少卡顿恢复。** 原逻辑要求已经渲染首帧，无法处理首次播放无数据的情况；BUFFERING 回调早于 Success 状态发布时也可能错过恢复任务。本次补齐首帧前保护及发布后的补调度。切换继续校验播放器、媒体身份、请求代次和已尝试地址；用户暂停取消等待，候选用尽后不循环切线。

这些条件与用户描述相符，但未取得用户现场记录，不能认定每一例意外暂停或不加载均已定位。

## 源码索引

BiliPai：

- `app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt`：初始取流、设备预热、密钥查询。
- `core-data/src/main/java/com/android/purebilibili/core/network/WbiKeyManager.kt`：持久缓存与并发刷新。
- `core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt`：API/媒体客户端、代理与超时。
- `core-data/src/main/java/com/android/purebilibili/data/repository/PlaybackQualityDiscovery.kt`：额外画质补查。
- `app/src/main/java/com/android/purebilibili/feature/video/state/VideoPlayerState.kt`：缓冲策略、会话暂停恢复。
- `app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt`：加载阶段、异常出口、CDN 恢复。
- `app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PiliCdnService.kt`：媒体 URL 选址。

PiliPlus（仅作为本次对比资料）：

- `lib/http/video.dart`、`lib/pages/video/controller.dart`：取流参数、画质补查和媒体选择。
- `lib/utils/wbi_sign.dart`：持久化签名缓存。
- `lib/http/init.dart`、`lib/utils/accounts.dart`：API 超时与账号激活。
- `lib/utils/video_utils.dart`：CDN 选择。
- `lib/plugin/pl_player/controller.dart`、`lib/utils/storage_pref.dart`：媒体打开、播放暂停、缓冲参数。

## 检查范围与后续

已检查代码差异、相关调用点和本地 Media3 API 源码；补充了倍速缓冲可达性、画质补查超时/取消，以及首帧前切线边界的回归测试源码。按项目约定没有编译或执行 Gradle 测试，未实测首帧耗时。

下一步可完善起播分段诊断：分别记录详情返回、密钥准备、取流返回、媒体首字节、播放器 READY、首帧和暂停原因，并按当前媒体身份关联。这样可以把用户报告的慢起播分为 API 等待、CDN 数据等待、解码出画或状态门控，而不是仅用一个总耗时推断原因。
