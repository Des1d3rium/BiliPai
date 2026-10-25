package com.android.purebilibili.feature.plugin.btr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.btr.BtrCdnMode
import com.android.purebilibili.btr.BtrSettings
import com.android.purebilibili.btr.BtrStats
import com.android.purebilibili.btr.normalizeBtrCdnHost
import com.android.purebilibili.core.ui.components.AppFilterChip
import com.android.purebilibili.core.ui.components.AppSwitchPreference
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.core.ui.components.AppTextField
import java.util.Locale

private val MODE_LABELS = listOf(
    BtrCdnMode.MAINLAND to "大陆 CDN",
    BtrCdnMode.OVERSEAS to "海外 CDN",
    BtrCdnMode.CUSTOM to "自定义"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BtrSettingsContent(
    config: BtrPluginConfig,
    stats: BtrStats,
    saving: Boolean,
    error: String?,
    onChange: (BtrPluginConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppText("线程撕裂者", style = MaterialTheme.typography.titleMedium)
        AppText(
            "把 B 站视频分段再拆成多个字节块，分给多个 CDN 节点并发下载，按顺序交给播放器。" +
                "适合海外观看冷门、高码率视频时单连接被限速的情况。设置在下一个请求生效。",
            style = MaterialTheme.typography.bodySmall
        )

        AppText("CDN", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MODE_LABELS.forEach { (mode, label) ->
                AppFilterChip(
                    selected = config.mode == mode.name,
                    enabled = !saving,
                    onClick = { onChange(config.copy(mode = mode.name)) },
                    label = { AppText(label) }
                )
            }
        }
        AppText(
            when (config.mode) {
                BtrCdnMode.OVERSEAS.name -> "海外节点就近取数据；本地到大陆网络很差时使用。"
                BtrCdnMode.CUSTOM.name -> "只用下面填写的节点（bilivideo.com、akamaized.net 等）；一个都没填时按大陆 CDN。"
                else -> "直接去大陆节点取数据，不在没有缓存的海外节点上干等。离得远时要配合多线程。"
            },
            style = MaterialTheme.typography.bodySmall
        )
        if (config.mode == BtrCdnMode.CUSTOM.name) {
            var draft by remember(config.customHosts) { mutableStateOf(config.customHosts.joinToString("\n")) }
            val parsed = draft.lines().map(::normalizeBtrCdnHost).filter { it.isNotEmpty() }.distinct()
            AppTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = "节点，每行一个",
                singleLine = false,
                minLines = 3,
                supportingText = { AppText("可用 ${parsed.size} 个，其他域名会被忽略") }
            )
            AppTextButton(
                onClick = { onChange(config.copy(customHosts = parsed)) },
                enabled = !saving && parsed != config.customHosts
            ) { AppText("保存节点") }
        }

        AppSwitchPreference(
            title = "自动线程数",
            subtitle = "在 8 到 32 之间自己调整：下载跟不上播放时增加，增加后没有变快就退回；服务器拒绝时降低。",
            checked = config.autoConcurrency,
            enabled = !saving,
            onCheckedChange = { onChange(config.copy(autoConcurrency = it)) }
        )
        if (!config.autoConcurrency) {
            AppText("线程数：${config.concurrency}", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BtrSettings.ALLOWED_CONCURRENCY.forEach { count ->
                    AppFilterChip(
                        selected = config.concurrency == count,
                        enabled = !saving,
                        onClick = { onChange(config.copy(concurrency = count)) },
                        label = { AppText("$count") }
                    )
                }
            }
            AppText(
                "线程数是同时进行的请求数，不保证等量的 TCP 连接：同一节点上的请求共用一条 HTTP/2 连接，" +
                    "带宽主要靠分散到多个节点。线程越多越容易触发 B 站限流（412/429）。",
                style = MaterialTheme.typography.bodySmall
            )
        }
        AppSwitchPreference(
            title = "抗波动缓冲",
            subtitle = "播放器多缓冲一些（开头 15 秒、之后至少 20 秒），节点卡顿几秒时不会停下。" +
                "海外 CDN 默认开启。下一个打开的视频生效。",
            checked = config.stableBufferEnabled(),
            enabled = !saving,
            onCheckedChange = { onChange(config.copy(stableBuffer = it)) }
        )
        AppSwitchPreference(
            title = "仅 Wi-Fi",
            subtitle = "关闭后移动网络也会并发下载，流量与耗电会增加。",
            checked = config.wifiOnly,
            enabled = !saving,
            onCheckedChange = { onChange(config.copy(wifiOnly = it)) }
        )
        error?.let { AppText(it, color = MaterialTheme.colorScheme.error) }

        AppText("传输状态", style = MaterialTheme.typography.titleSmall)
        AppText("线程 ${stats.threads} · 活动请求 ${stats.activeRequests} · 前方缓冲 ${stats.bufferAheadMs / 1000} 秒")
        stats.autoReason?.let { AppText("自动线程数：$it", style = MaterialTheme.typography.bodySmall) }
        AppText(
            String.format(
                Locale.ROOT, "近 5 秒 %.1f Mbps · 累计 %.1f MiB · 区间 %d · 回退 %d",
                stats.speedBps * 8.0 / 1_000_000, stats.deliveredBytes / 1_048_576.0, stats.rangeCount, stats.fallbackCount
            )
        )
        if (stats.nodes.isNotEmpty()) {
            AppText("当前视频的节点", style = MaterialTheme.typography.titleSmall)
            stats.nodes.forEach { node ->
                val state = when (node.state) {
                    "healthy" -> "可用"
                    "blocked" -> "暂停"
                    "banned" -> "已停用"
                    else -> "未测"
                }
                AppText(
                    String.format(Locale.ROOT, "%s · %s · %.1f Mbps", node.host, state, node.bps * 8 / 1_000_000),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        AppText(
            "失败时自动回到 BiliPai 原来的下载方式继续，不影响播放。直播、番剧外的未知长度请求不经过本插件。" +
                "移植自 Bilibili-thread-ripper（MIT），并非原作者官方发布。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
