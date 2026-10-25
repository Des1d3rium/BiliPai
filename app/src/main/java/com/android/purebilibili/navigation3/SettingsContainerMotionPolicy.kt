package com.android.purebilibili.navigation3

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp

internal const val SETTINGS_CONTAINER_DURATION_MILLIS = 300

internal data class SettingsContainerFrame(
    val bounds: Rect,
    val contentAlpha: Float,
    val cornerFraction: Float,
)

/** 导航深度是唯一时钟；程序返回、跟手及取消返回都沿同一几何路径。 */
internal fun resolveSettingsContainerFrame(
    source: Rect,
    destination: Rect,
    relativeDepth: Float,
    returning: Boolean,
): SettingsContainerFrame {
    val open = (1f + relativeDepth.coerceAtMost(0f)).coerceIn(0f, 1f)
    val geometry = if (returning) {
        1f - FastOutSlowInEasing.transform(1f - open)
    } else FastOutSlowInEasing.transform(open)
    val fadeStart = if (returning) 0.6f else 0.2f
    return SettingsContainerFrame(
        bounds = lerp(source, destination, geometry),
        contentAlpha = ((open - fadeStart) / 0.2f).coerceIn(0f, 1f),
        cornerFraction = 1f - geometry,
    )
}

/** 来源必须属于紧邻的父 entry，防止搜索或其他页面复用同名目的地的旧来源。 */
internal fun isSettingsContainerBoundary(
    enabled: Boolean,
    backStack: List<BiliPaiNavKey>,
    target: BiliPaiNavKey,
    owner: BiliPaiNavKey?,
): Boolean {
    if (!enabled || owner == null) return false
    val index = backStack.indexOf(target)
    return index > 0 && backStack[index - 1] == owner
}

/** 父页深度从 0 到 1 时逐渐模糊，前景页及非有限输入不参与。 */
internal fun resolveSettingsContainerBlurProgress(relativeDepth: Float): Float {
    if (!relativeDepth.isFinite()) return 0f
    val covered = relativeDepth.coerceIn(0f, 1f)
    return covered * covered
}
