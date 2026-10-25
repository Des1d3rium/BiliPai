package com.android.purebilibili.feature.plugin.btr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.btr.BtrCdnMode
import com.android.purebilibili.btr.BtrSettings
import com.android.purebilibili.btr.BtrTransport
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.plugin.BuiltinPlugin
import com.android.purebilibili.plugin.sdk.PluginCapability
import com.android.purebilibili.plugin.sdk.PluginCapabilityManifest
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.core.plugin.PluginStore
import com.android.purebilibili.core.util.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val BTR_PLUGIN_ID = "btr_thread_ripper"

@Serializable
internal data class BtrPluginConfig(
    val mode: String = BtrCdnMode.MAINLAND.name,
    val customHosts: List<String> = emptyList(),
    val concurrency: Int = 8,
    val autoConcurrency: Boolean = true,
    val wifiOnly: Boolean = true,
    /** Null follows the mode: on for overseas nodes, which stall often. */
    val stableBuffer: Boolean? = null,
    /** BTR's multi-node download; the anti-jitter buffer works without it. */
    val transfer: Boolean = true
) {
    fun stableBufferEnabled(): Boolean = stableBuffer ?: (normalized().mode == BtrCdnMode.OVERSEAS.name)

    fun normalized(): BtrPluginConfig = copy(
        mode = runCatching { BtrCdnMode.valueOf(mode).name }.getOrDefault(BtrCdnMode.MAINLAND.name),
        concurrency = concurrency.takeIf { it in BtrSettings.ALLOWED_CONCURRENCY } ?: 8
    )

    fun toSettings(enabled: Boolean): BtrSettings = BtrSettings(
        enabled = enabled,
        mode = BtrCdnMode.valueOf(normalized().mode),
        customHosts = customHosts,
        concurrency = concurrency,
        autoConcurrency = autoConcurrency,
        stableBuffer = stableBufferEnabled(),
        transfer = transfer
    ).normalized()
}

/** Bilibili-thread-ripper's downloader for on-demand DASH playback; see :btr-transport. */
class BtrPlugin : BuiltinPlugin {
    override val id = BTR_PLUGIN_ID
    override val name = "线程撕裂者"
    override val description = "按 BTR 原版移植：多节点分片并发、对冲补救、断点续传、自动线程数"
    override val version = "1.0.0"
    override val author = "BTR contributors / BiliPai fork"
    override val icon: ImageVector = Icons.Outlined.Speed
    override val capabilityManifest = PluginCapabilityManifest(
        pluginId = id, displayName = name, version = version, apiVersion = 1,
        entryClassName = "com.android.purebilibili.feature.plugin.btr.BtrPlugin",
        capabilities = setOf(PluginCapability.PLAYBACK_CDN, PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val config = MutableStateFlow(BtrPluginConfig())
    private val mutex = Mutex()
    @Volatile private var running = false

    override suspend fun onEnable() = mutex.withLock {
        val context = PluginManager.getContext()
        config.value = PluginStore.getConfigJson(context, id)
            ?.let { runCatching { json.decodeFromString<BtrPluginConfig>(it).normalized() }.getOrNull() }
            ?: BtrPluginConfig()
        running = true
        apply(config.value)
    }

    override suspend fun onDisable() = mutex.withLock {
        running = false
        BtrTransport.disable()
    }

    private fun apply(next: BtrPluginConfig) {
        val context = PluginManager.getContext().applicationContext
        BtrTransport.configure(
            next.toSettings(enabled = running), NetworkModule.playbackOkHttpClient,
            allowNetwork = { !next.wifiOnly || NetworkUtils.isWifi(context) },
            storageDir = context.filesDir
        )
    }

    private suspend fun update(next: BtrPluginConfig) = mutex.withLock {
        val normalized = next.normalized()
        PluginStore.setConfigJson(PluginManager.getContext(), id, json.encodeToString(normalized))
        config.value = normalized
        if (running) apply(normalized)
    }

    @Composable
    override fun SettingsContent() = SettingsContent(Modifier)

    @Composable
    override fun SettingsContent(modifier: Modifier) {
        val settings by config.collectAsStateWithLifecycle()
        val stats by BtrTransport.stats.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        BtrSettingsContent(
            config = settings, stats = stats, saving = saving, error = error, modifier = modifier,
            onChange = { next ->
                if (!saving) {
                    saving = true
                    scope.launch {
                        try {
                            update(next)
                            error = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = "保存失败，请重试"
                        } finally {
                            saving = false
                        }
                    }
                }
            }
        )
    }
}
