<div align="center">

<img src="docs/images/233娘.jpeg" height="96" alt="BiliPai" />

# BiliPai

**原生、纯净、可扩展的第三方 BiliBili Android 客户端**

[简体中文](README.md) · [English](README_EN.md)

<img src="https://img.shields.io/badge/Source-0.5.4-007AFF?style=flat-square" alt="Source 0.5.4" />
<img src="https://img.shields.io/badge/Android-8.0%2B-34C759?style=flat-square" alt="Android 8.0+" />
<img src="https://img.shields.io/badge/License-GPLv3-FF3B30?style=flat-square" alt="GPLv3" />

[Telegram 频道](https://t.me/bilipai666) · [Telegram 交流群](https://t.me/bilipai888/1) · [X](https://x.com/YangY_0x00)

<sub>源码版本 0.5.4（502） · 更新于 2026-10-22</sub>

</div>

BiliPai 基于 Kotlin 与 Jetpack Compose，支持视频、番剧、直播、动态、消息和离线缓存，适配手机、平板与折叠屏，后续会提供独立电视端。

[下载](#下载与安装) · [功能](#主要功能) · [预览](#界面预览) · [构建](#构建) · [更新日志](CHANGELOG.md)

## 主要功能

| 功能 | 说明 |
| --- | --- |
| 视频播放 | DASH、高清画质、弹幕、手势、倍速、播放记忆、后台播放与画中画 |
| 听视频 | 沉浸式与黑胶模式、歌词、队列、定时关闭、系统媒体控制 |
| 字幕与笔记 | 本地字幕导入导出、样式与时间调整；私有笔记、时间点与 AI 总结草稿 |
| 番剧与直播 | 追番、选集、进度预览、直播分区与实时弹幕 |
| 动态与消息 | 关注动态、评论回复、图片预览与私信 |
| 搜索与缓存 | 视频、UP 主与番剧搜索；离线下载、断点续传与本地播放 |
| 外观与布局 | Material 3、Miuix、可选液态玻璃、动态取色与大屏布局 |
| 扩展能力 | 插件、DLNA / Google Cast 投屏、WebDAV 设置备份 |

部分画质需要登录或相应账号权限。外观、播放、弹幕与后台行为可在设置中调整。

## 界面预览

界面随版本、主题和设备尺寸有所不同。

<div align="center">

<img src="docs/images/screenshot1.png" height="400" alt="BiliPai 1" />
<img src="docs/images/screenshot2.png" height="400" alt="BiliPai 2" />
<img src="docs/images/screenshot3.png" height="400" alt="BiliPai 3" />
<img src="docs/images/screenshot4.png" height="400" alt="BiliPai 4" />
<img src="docs/images/screenshot5.png" height="400" alt="BiliPai 5" />
<img src="docs/images/screenshot6.png" height="400" alt="BiliPai 6" />
<img src="docs/images/screenshot7.png" height="400" alt="BiliPai 7" />
<img src="docs/images/screenshot8.png" height="400" alt="BiliPai 8" />
<img src="docs/images/screenshot9.png" height="400" alt="BiliPai 9" />

</div>

## 下载与安装

从 [Telegram 频道](https://t.me/bilipai666) 获取安装包与发布说明，交流反馈可加入 [Telegram 群组](https://t.me/bilipai888/1)。

- 系统要求：Android 8.0+，架构以安装包为准。
- 登录方式：TV 扫码、密码、短信验证码或 Cookie 导入。
- 安装 APK 时需允许对应来源安装应用。

## 插件

内置空降助手、去广告、Anime4K、弹幕增强、夜间护眼、推荐与投屏等 12 个插件。JSON / `.bp` 规则支持 URL 导入；源码插件通过编译集成，外部 `.bpplugin` 目前仅预览、保存与记录授权，不加载或执行外部代码。

- `plugin-sdk` 是执行契约与共用模型的唯一来源；App 的 `BuiltinPlugin` 只增加展示与设置界面，`PluginManager` 统一注册、查询和启停。
- 播放器能力以 `PlayerPluginApi.createSession()` 为每个播放器和视频创建独立会话；信息流与推荐能力以泛型直接接收宿主视频对象。弹幕样式颜色使用 ARGB 整数，`null` 表示未指定。
- 本次为 `0.1.0-SNAPSHOT` 的源码级破坏性调整：插件作者需迁移基础接口、播放器会话及推荐泛型并重新编译，不保留旧宿主接口别名。独立 SDK 示例见 `plugins/samples/watch-compass` 与 `plugins/samples/today-watch-remix`；两者使用本地 SDK，并与主项目保持 AGP 9.3.2／Kotlin 2.4.0 一致。

导入第三方插件前，请检查来源与权限声明。

## 最近更新

- 新增英文支持、本地字幕导入导出与样式调整。
- 完善播放器菜单、番剧进度预览和合集付费标识。
- 修复收藏夹多选，统一资料与侧栏图标，补充启动和交互性能基线。

完整记录见 [CHANGELOG.md](CHANGELOG.md)，源码版本以 `app/build.gradle.kts` 为准。

## 项目结构

```text
BiliPai/
├── app/                     # 手机与平板应用
├── app-tv/                  # 电视端应用
├── core-data/               # 网络、数据与存储
├── core-player/             # 播放会话与媒体处理
├── design-system/           # 主题与通用组件
├── design-tokens/           # 颜色、字体与尺寸
├── brand-motion/            # 品牌资源与动效
├── settings-core/           # 设置策略
├── network-core/            # 网络与推荐策略
├── plugin-sdk/              # 插件接口
├── plugins/                 # 插件与皮肤示例
├── danmaku-engine/          # 弹幕引擎
├── dolby-ffmpeg-decoder/    # 音频解码扩展
├── miuix-navigation/        # 导航组件
├── baselineprofile/         # 启动与交互性能基线
├── ci-lint/                 # 静态检查规则
├── build-logic/             # 构建插件
├── gradle/                  # 构建依赖与 Wrapper
├── scripts/                 # 构建与维护脚本
└── docs/                    # 截图与来源资料
```

## 构建

环境：JDK 21、Android SDK 37；Gradle Wrapper 与依赖版本由仓库管理。

Miuix 依赖从 GitHub Packages 获取，需在本机 `~/.gradle/gradle.properties` 配置 `gpr.user`、`gpr.key`，或设置 `GITHUB_ACTOR`、`GITHUB_TOKEN` 环境变量。凭据需有对应包的读取权限。

```bash
git clone https://github.com/jay3-yy/BiliPai.git
cd BiliPai
./gradlew :app:assembleDev
```

APK 输出至 `app/build/outputs/bilipai/dev/`；正式版输出至 `app/build/outputs/bilipai/release/`。

`app/google-services.json` 为可选配置，缺失时跳过 Firebase。主要技术栈为 Compose、Media3、Room、DataStore、Retrofit、OkHttp 与 Coil。

## 参与贡献

欢迎通过 [Issue](https://github.com/jay3-yy/BiliPai/issues/new/choose) 反馈问题、提出建议，也欢迎提交 [Pull Request](https://github.com/jay3-yy/BiliPai/pulls)，一起完善功能和翻译。

## 致谢

| 项目 | 用途 |
| --- | --- |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | 声明式 UI 框架 |
| [AndroidX Media](https://github.com/androidx/media) | Media3 / ExoPlayer 播放引擎 |
| [DanmakuRenderEngine](https://github.com/bytedance/DanmakuRenderEngine) | 高性能弹幕渲染参考 |
| [BiliBili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) | B 站 API 文档 |
| [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) | 播放链路与移动端体验参考 |
| [Bili Pilot](https://github.com/siwei-yuan/bili-pilot) | CDN 选线与预缓存设计参考 |
| [BiliBiliSponsorBlock](https://github.com/hanydd/BilibiliSponsorBlock) | 空降助手数据与 API |
| [Haze](https://github.com/chrisbanes/haze) | 毛玻璃效果 |
| [Compose Cupertino](https://github.com/alexzhirkevich/compose-cupertino) | iOS 风格组件 |
| [Miuix](https://github.com/compose-miuix-ui/miuix) | 界面组件与液态玻璃动效参考 |
| [FlClash](https://github.com/chen08209/FlClash) | 设置容器展开及玻璃指示器动效参考 |
| [BiliPai-miuix](https://github.com/Piracola/BiliPai-miuix) | UI 组件与设计系统贡献（@piracola） |
| [Lottie](https://github.com/airbnb/lottie-android) | 矢量动画 |
| [Coil](https://github.com/coil-kt/coil) | 图片加载 |
| [Retrofit](https://github.com/square/retrofit) / [OkHttp](https://github.com/square/okhttp) | 网络请求 |
| [Room](https://developer.android.com/training/data-storage/room) / [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) | 本地数据与偏好存储 |
| [TabulaBili](https://github.com/wangdaodaodao/TabulaBili) / [TabulaBili-Plus](https://github.com/tjsky/TabulaBili) | 初见推荐参考 |
| [biliSendCommAntifraud](https://github.com/freedom-introvert/biliSendCommAntifraud) | 评论反诈检测参考 |
| [Kotlin](https://kotlinlang.org/) | 开发语言 |
| [Kotlinx Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 协程与异步任务 |
| [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization) | 数据序列化 |
| [MaterialKolor](https://github.com/jordond/MaterialKolor) | 动态主题配色 |
| [Compose Rich Editor](https://github.com/MohamedRejeb/Compose-Rich-Editor) | 富文本编辑 |
| [ZXing](https://github.com/zxing/zxing) | 二维码处理 |
| [CameraX](https://developer.android.com/media/camera/camerax) | 扫码相机 |
| [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) | 后台任务调度 |
| [FFmpeg](https://ffmpeg.org/) | 音频解码 |
| [Readability4J](https://github.com/dankito/Readability4J) | 文章正文提取 |
| [jsoup](https://jsoup.org/) | HTML 解析 |
| [Telegram](https://github.com/DrKLO/Telegram) | 粒子动效与资料页交互参考 |

更多来源与许可证见 [第三方声明](THIRD_PARTY_NOTICES.md)。

## 许可证与声明

本项目采用 [GPLv3](LICENSE)。分发时请遵守许可证，提供对应源代码并保留版权声明。

本项目为非官方客户端，与 BiliBili 无隶属关系。内容版权归对应权利方所有，使用时请遵守相关法律法规与平台规则。登录信息保存在本地；使用统计可在设置中管理。

<div align="center">

Made by [YangY](https://x.com/YangY_0x00)

</div>
