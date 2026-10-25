# 分 P 继续观看与推荐反馈对比分析

日期：2026-10-25。对比对象为桌面 PiliPlus 源码（`3d0aed958`）及当前 BiliPai 工作区。以下结论来自源码，不代表已经重现每位用户的现场问题，也不表示客户端可以控制服务端推荐排序。

## 分 P 提示为什么找不到、打开也没效果

存在两个不同功能：

- “继续播放提示”根据本机保存的观看进度，建议继续其他分 P 或合集内容，并对目标做一次性提醒。
- 原“显示继续播放分P提示”用于显示“上次看到第 N P，点击跳转”，依赖服务端上次观看分 P 记录。

原搜索目录把后者登记在“字幕与连播”，实际控件却写在“全屏与手势”函数中，因此分类页与搜索落点不一致。本次移至 **设置 → 播放与画质 → 播放设置 → 字幕与连播**，名称改为“上次观看分P跳转提示”，保留旧名称和“定位分P”“上次看到”等搜索别名；保存键保持不变。

PiliPlus 的 `lib/pages/video/controller.dart::_queryPlayInfo` 从播放器信息响应 `last_play_cid` 读取观看记录，然后在多 P 目录中定位目标。BiliPai 修改前只读取取流响应 `PlayUrlData.lastPlayCid`，播放器信息模型 `PlayerInfoData` 没有解析该字段，即使 `/x/player/wbi/v2` 返回记录也会丢失。

本次为播放器信息模型补上可空的 `lastPlayCid`，在已有异步信息请求返回后补齐提示，不增加首帧前网络等待。缺失字段与真实 0 保持不同含义；请求代次、视频和分 P 必须仍匹配，且手动关闭后不恢复提示。普通、横屏及大屏沿用共享播放器提示，竖屏翻页播放器补上相同提示入口。相邻设置的历史图标原先实际同形，本次使用分 P 列表与返回箭头区分跳转提示。

仍然只有“多 P 视频且上次观看的是另一 P、目标在当前目录中”才有提示；本次不会让开关变成自动跳转，也不会在当前已经是上次观看分 P 时重复提示。

## 推荐不符合观看习惯：确认的差异与可能原因

| 检查项 | PiliPlus | BiliPai 修改前与本次处理 |
| --- | --- | --- |
| Web 推荐参数 | `feed_version=V8`、`fresh_type=4`、`version=1`、`homepage_ver=1`、`fresh_idx` 与 `brush` 同步 | 单独 Web 模式使用时间戳版本、`fresh_type=3` 和 `y_num`；合并模式才使用 V8。本次统一两条 Web 路径的协议参数，并检查服务端错误码 |
| 移动端推荐 | `build=8430300`、`column=2`、`device=phone`、`mobi_app=android_i` 等 | 目前仍用 `build=2001100`、`column=4`、`device=pad`、`mobi_app=android_hd` 等。接口相同不等于推荐分布相同；本次未在没有响应样本的情况下整体替换设备、签名和登录身份 |
| 推荐账号 | 使用 `AccountType.recommend` 对应账号 | 首页推荐跟随主登录账号；独立播放账号不会自动成为推荐账号。需要核对用户实际使用的账号及观看记录 |
| 匿名推荐 | 由账号及请求设置决定 | “初见推荐”插件会刻意移除 Web 首页 Cookie，使结果更接近未登录公共热门。不能在未知用户配置时断言该插件已开启，也不能把这种模式当成个性化推荐 |
| 观看信号 | 播放心跳、历史和账号状态参与服务端行为 | 本地屏蔽规则、卡片过滤、今日推荐计划不能替代服务端画像。用户关闭历史上报或账号不同也值得核查，尚无现场证据证明发生 |

Web 参数差异是可核实的请求问题；它对某位用户推荐结果的具体影响仍需响应样本。服务端排序、推荐来源和用户账号都可能影响结果，不能承诺改参数后立即贴合观看习惯。

## “不感兴趣”究竟生效在哪里

PiliPlus 使用 `/x/feed/dislike`，要求推荐账号有 access key，并提交卡片携带的 `goto`、资源 ID 与服务端 `reason_id` 或 `feedback_id`；不能只凭“我点了这个按钮”推断反馈已入服务端。

BiliPai 已有同一类服务端反馈接口，移动端卡片会映射服务端反馈原因；但 Web 卡片没有对应反馈元数据，回退选项也没有服务端原因 ID。因此：

1. Web 来源或无服务端原因的选项，只能在本地移除视频、过滤所选 UP 或关键词；不能训练服务端推荐。
2. 有反馈元数据但缺少移动端凭证时，服务器同步失败；当前本地移除仍然生效。
3. 提交成功也只是服务端接受反馈，不代表下一页立刻全面改变。

修改前，本地回退分支仍显示“已减少相关内容推荐”，容易让用户以为推荐画像已经改变。本次将说明与结果区分为“仅在本地屏蔽”“已提交推荐反馈”和“本地生效但服务器同步失败”，并保留取消操作、账号切换保护及失败反馈。未凭空补造反馈原因 ID，也未把内容点踩接口当成推荐反馈接口。

## 源码依据

PiliPlus：

- `lib/pages/video/controller.dart::_queryPlayInfo`、`lib/models_new/video/video_play_info/data.dart`：分 P 观看记录。
- `lib/http/video.dart::rcmdVideoList`、`rcmdVideoListApp`、`feedDislike`：两类推荐请求和负反馈。
- `lib/pages/rcmd/controller.dart`、`lib/utils/accounts.dart`：推荐来源与账号。

BiliPai：

- `core-data/.../response/PlayerInfoResponse.kt`、`feature/video/viewmodel/VideoPlaybackViewModel.kt`：记录解析与异步提示更新。
- `feature/settings/PlaybackSettingDirectory.kt`、`screen/PlaybackSettingsScreen.kt`：设置目录和实际控件。
- `data/repository/VideoRepository.kt`：Web/移动端推荐请求。
- `data/repository/ActionRepository.kt`、`feature/home/HomeViewModel.kt`：服务端反馈与本地过滤。
- `feature/plugin/HomeFeedAnonymizerPlugin.kt`、`core-data/.../network/AccountCookieSession.kt`：匿名推荐的显式开关与 Cookie 处理。

## 检查与未完成验证

已做源码、调用路径、差异和相邻图标字形检查；补充了播放器信息解码、分 P 目标选择、设置搜索落点和 Web 分页参数回归测试源码。遵照项目约定没有编译或运行 Gradle 测试，未实测推荐结果与服务器反馈。

后续可以增加推荐问题诊断，明确展示当前来源、是否匿名、账号身份是否一致，以及最近一次反馈是本地生效、服务器接受还是失败；再按脱敏响应对比移动端参数和反馈效果，避免把推荐不匹配全部归因于某一个字段。
