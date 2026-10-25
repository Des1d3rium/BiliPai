package com.android.purebilibili.navigation3

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.android.purebilibili.core.ui.transition.BlurRenderEffectCache
import com.android.purebilibili.core.ui.transition.resolvePredictiveBackBlurFrame
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import com.android.purebilibili.feature.settings.SettingsContainerSource
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavSettlePhase
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope

/** 只负责设置导航边界的绘制，页面始终按最终尺寸布局。 */
internal class SettingsContainerTransition(
    private val source: SettingsContainerSource,
    private val pageColor: androidx.compose.ui.graphics.Color,
    private val realtimeBlurEnabled: Boolean = false,
    private val blurMotionTierProvider: () -> MotionTier = { MotionTier.Normal },
) : NavTransition {
    // 绘制阶段锁存完成状态；入场未完成就返回时沿原曲线折返，不切换几何路径。
    private var hasOpened = false
    private val blurCache = BlurRenderEffectCache()
    override val handlesOwnClipping = true
    override val motion = NavMotion(
        programmatic = NavSettleSpec.Tween(SETTINGS_CONTAINER_DURATION_MILLIS, LinearEasing),
    )

    override fun scrimFraction(scope: NavTransitionScope): Float = 0f

    override fun Modifier.transformEntry(scope: NavTransitionScope): Modifier = this
        .then(if (realtimeBlurEnabled) Modifier.graphicsLayer {
            // 只模糊被覆盖的父设置页；当前页文字与容器轮廓保持清晰。
            // 进度在图层读取，进入、返回与取消共用导航时钟，不录制静态背景。
            val progress = resolveSettingsContainerBlurProgress(scope.relativeDepth)
            val frame = resolvePredictiveBackBlurFrame(
                progress = progress,
                motionTier = blurMotionTierProvider(),
                isLightBackground = pageColor.luminance() > 0.5f,
            )
            renderEffect = blurCache.resolve(frame.blurRadiusPx)
        } else Modifier)
        .drawWithContent {
            // 被覆盖页保持静止；后续子页依然由各自的导航边界决定动效。
            if (scope.role == NavRole.Covered || scope.relativeDepth > 0f) {
                drawContent()
                return@drawWithContent
            }
            if (scope.relativeDepth == 0f) {
                hasOpened = true
                drawContent()
                return@drawWithContent
            }
            val destination = Rect(Offset.Zero, size)
            if (!source.isUsable(scope.layoutSize)) {
                // 窗口变化或来源已卸载时只淡化，不能收回旧窗口中的错误位置。
                val alpha = (1f + scope.relativeDepth).coerceIn(0f, 1f)
                drawContext.canvas.saveLayer(destination, Paint().apply { this.alpha = alpha })
                drawContent()
                drawContext.canvas.restore()
                return@drawWithContent
            }
            val frame = resolveSettingsContainerFrame(
                source.bounds, destination, scope.relativeDepth,
                returning = hasOpened && (scope.change == NavChange.Pop || scope.gesture != null ||
                    scope.change is NavChange.MultiPop || scope.settle?.phase == NavSettlePhase.Cancel ||
                    scope.settle?.phase == NavSettlePhase.Commit),
            )
            val top = CornerRadius(source.topRadius * frame.cornerFraction)
            val bottom = CornerRadius(source.bottomRadius * frame.cornerFraction)
            val path = Path().apply {
                addRoundRect(RoundRect(frame.bounds, top, top, bottom, bottom))
            }
            clipPath(path) {
                drawRect(lerp(source.color, pageColor, frame.contentAlpha), frame.bounds.topLeft, frame.bounds.size)
                // 源行与目标页共用一个透明度时钟；目标页出现后不残留源文字和图标。
                val sourceAlpha = 1f - frame.contentAlpha
                if (sourceAlpha > 0f) {
                    if (sourceAlpha < 1f) {
                        drawContext.canvas.saveLayer(frame.bounds, Paint().apply { alpha = sourceAlpha })
                    }
                    // 仅重放当前行自己的图层，避免递归采集整页或玻璃背景。
                    translate(frame.bounds.left, frame.bounds.top) {
                        val factor = frame.bounds.width / source.bounds.width
                        scale(factor, factor, pivot = Offset.Zero) { drawLayer(source.layer) }
                    }
                    if (sourceAlpha < 1f) drawContext.canvas.restore()
                }
                if (frame.contentAlpha > 0f) {
                    drawContext.canvas.saveLayer(destination, Paint().apply { alpha = frame.contentAlpha })
                    // 页面按原始大小渲染，容器边界移动而不是反复测量页面。
                    translate(frame.bounds.left, frame.bounds.top) { this@drawWithContent.drawContent() }
                    drawContext.canvas.restore()
                }
            }
        }
}
