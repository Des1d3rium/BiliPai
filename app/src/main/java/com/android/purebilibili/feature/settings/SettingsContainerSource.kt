package com.android.purebilibili.feature.settings

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.IntSize
import com.android.purebilibili.navigation3.BiliPaiNavKey

/** 坐标只由布局回调更新，不让测量结果反向触发页面组合。 */
internal class SettingsContainerAnchor {
    var coordinates: LayoutCoordinates? = null
    var recorded = false
    var boundsInRoot: Rect? = null

    fun update(value: LayoutCoordinates) {
        coordinates = value
        boundsInRoot = if (value.isAttached) value.boundsInRoot() else null
    }

    fun detach() {
        coordinates = null
        boundsInRoot = null
        recorded = false
    }
}

internal class SettingsContainerSource(
    val anchor: SettingsContainerAnchor,
    var layer: GraphicsLayer,
    val owner: BiliPaiNavKey,
    val bounds: Rect,
    val hostSize: IntSize,
    val originInRoot: Offset,
    val color: Color,
    val topRadius: Float,
    val bottomRadius: Float,
) {
    fun isUsable(size: IntSize): Boolean {
        val liveBounds = anchor.boundsInRoot?.translate(-originInRoot) ?: return false
        return anchor.coordinates?.isAttached == true && anchor.recorded && size == hostSize &&
            bounds.width > 1f && bounds.height > 1f &&
            kotlin.math.abs(liveBounds.left - bounds.left) < 1f &&
            kotlin.math.abs(liveBounds.top - bounds.top) < 1f &&
            kotlin.math.abs(liveBounds.width - bounds.width) < 1f &&
            kotlin.math.abs(liveBounds.height - bounds.height) < 1f
    }
}

internal class SettingsContainerSources {
    val sources = mutableStateMapOf<BiliPaiNavKey, SettingsContainerSource>()
    var hostOrigin = Offset.Zero
    var hostSize = IntSize.Zero

    fun capture(
        target: BiliPaiNavKey,
        owner: BiliPaiNavKey,
        anchor: SettingsContainerAnchor,
        layer: GraphicsLayer,
        color: Color,
        topRadius: Float,
        bottomRadius: Float,
    ) {
        val coordinates = anchor.coordinates ?: return
        if (!coordinates.isAttached || !anchor.recorded || hostSize == IntSize.Zero) return
        // 点击时再采集一次，与布局回调共用同一份边界，避免缺失实时边界而误降级。
        anchor.update(coordinates)
        val bounds = resolveSettingsContainerCaptureBounds(
            boundsInRoot = anchor.boundsInRoot,
            rowSize = coordinates.size,
            hostOrigin = hostOrigin,
            hostSize = hostSize,
        ) ?: return
        sources[target] = SettingsContainerSource(
            anchor, layer, owner, bounds, hostSize, hostOrigin, color, topRadius, bottomRadius,
        )
    }
}

internal val LocalSettingsContainerSources = staticCompositionLocalOf<SettingsContainerSources?> { null }
internal val LocalSettingsContainerOwner = staticCompositionLocalOf<BiliPaiNavKey?> { null }

/** 部分可见或尚未完成布局的行不作为起点，完整行统一转换到导航宿主坐标。 */
internal fun resolveSettingsContainerCaptureBounds(
    boundsInRoot: Rect?,
    rowSize: IntSize,
    hostOrigin: Offset,
    hostSize: IntSize,
): Rect? {
    if (boundsInRoot == null || rowSize.width <= 1 || rowSize.height <= 1 ||
        hostSize.width <= 1 || hostSize.height <= 1) return null
    val bounds = boundsInRoot.translate(-hostOrigin)
    if (bounds.width < rowSize.width - 1f || bounds.height < rowSize.height - 1f ||
        bounds.left < 0f || bounds.top < 0f ||
        bounds.right > hostSize.width || bounds.bottom > hostSize.height) return null
    return bounds
}
