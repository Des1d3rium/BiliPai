package com.android.purebilibili.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import com.android.purebilibili.core.store.ThemeModeRoleOverrides
import com.android.purebilibili.core.store.ThemeRoleOverrides
import com.android.purebilibili.feature.settings.Md3ColorSource
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePreviewPolicyTest {
    @Test
    fun wallpaperModePreviewsUseActualLightAndDarkSchemesInsteadOfFallbackSeed() {
        val light = lightColorScheme(primary = Color(0xFF006A60), background = Color(0xFFF4FBF8))
        val dark = darkColorScheme(primary = Color(0xFF80D5C8), background = Color(0xFF101815))
        val wallpaper = ThemeWallpaperPreviewSchemes(light, dark)
        for (glass in listOf(false, true)) for (isDark in listOf(false, true)) {
            for (amoled in listOf(false, true)) {
                val configuration = ThemePreviewConfiguration(
                    source = Md3ColorSource.FOLLOW_WALLPAPER,
                    liquidGlassEnabled = glass,
                    amoledDarkTheme = amoled,
                    wallpaperSchemes = wallpaper,
                )
                val preview = resolveThemePreviewPalette(Color(0xFFFF4081), isDark,
                    PaletteStyle.TonalSpot, ColorSpec.SpecVersion.SPEC_2025, Contrast.Default,
                    SeedPaletteStyle.Standard, configuration).material
                val actual = if (isDark) dark else light
                assertEquals(actual.primary, preview.primary)
                assertEquals(if (isDark && amoled) Color.Black else actual.background, preview.background)
            }
        }
    }

    @Test
    fun customColorCandidatesIgnoreWallpaperPreviewSchemes() {
        val configuration = ThemePreviewConfiguration(source = Md3ColorSource.CUSTOM)
        for (dark in listOf(false, true)) {
            val expected = resolveThemePreviewPalette(seed, dark, PaletteStyle.TonalSpot,
                ColorSpec.SpecVersion.SPEC_2025, Contrast.Default, SeedPaletteStyle.Standard, configuration)
            val actual = resolveThemePreviewPalette(seed, dark, PaletteStyle.TonalSpot,
                ColorSpec.SpecVersion.SPEC_2025, Contrast.Default, SeedPaletteStyle.Standard,
                configuration.copy(wallpaperSchemes = ThemeWallpaperPreviewSchemes(
                    lightColorScheme(primary = Color.Red), darkColorScheme(primary = Color.Blue),
                )))
            assertEquals(expected.material.primary, actual.material.primary)
            assertEquals(expected.material.background, actual.material.background)
        }
    }

    private val seed = Color(0xFF34C759)
    private val overrides = ThemeRoleOverrides(
        enabled = true,
        light = ThemeModeRoleOverrides("#FFF4E8", "#EEEEEE", "#DDDDDD", "#8800AA"),
        dark = ThemeModeRoleOverrides("#130F19", "#222222", "#333333", "#8800AA"),
    )

    @Test
    fun previewMatchesFinalAppEndpointsAcrossBothNativeStylesGlassAndDarkModes() {
        for (uiStyle in AppUiStyle.entries) for (dark in listOf(false, true)) {
            for (glass in listOf(false, true)) for (seedStyle in listOf(SeedPaletteStyle.Standard, SeedPaletteStyle.CandyPop)) {
                val configuration = ThemePreviewConfiguration(
                    uiStyle = uiStyle, source = Md3ColorSource.CUSTOM,
                    liquidGlassEnabled = glass, amoledDarkTheme = true, roleOverrides = overrides,
                )
                val effective = resolveEffectiveThemeRoleOverrides(Md3ColorSource.CUSTOM, overrides, uiStyle)
                    .let { if (seedStyle.enabled) it.copy(enabled = false) else it }
                val base = createBiliPaiStyleColorScheme(
                    seed, dark, true, PaletteStyle.TonalSpot, ColorSpec.SpecVersion.SPEC_2025,
                    uiStyle = uiStyle, seedPaletteStyle = seedStyle,
                )
                val edited = applyThemeRoleOverrides(base, effective, dark)
                val configured = if (seedStyle.enabled) edited else alignLiquidAccentColorScheme(
                    edited, resolveLiquidControlAccentColor(
                        Md3ColorSource.CUSTOM, seed, edited.primary, effective.enabled,
                        if (dark) effective.dark.controlAccentHex else effective.light.controlAccentHex,
                    ), glass, Md3ColorSource.CUSTOM,
                )
                val expected = resolveAppThemeEndpointPalette(
                    configured, uiStyle, Md3ColorSource.CUSTOM, dark, amoledDarkTheme = true,
                    customSeed = seed, roleOverrides = effective, liquidGlassEnabled = glass,
                    preserveGeneratedPalette = seedStyle.enabled,
                )
                val preview = resolveThemePreviewPalette(seed, dark, PaletteStyle.TonalSpot,
                    ColorSpec.SpecVersion.SPEC_2025, Contrast.Default, seedStyle, configuration)
                assertEquals(expected.material.primary, preview.material.primary)
                assertEquals(expected.material.background, preview.material.background)
                assertEquals(expected.material.onSurface, preview.material.onSurface)
                assertEquals(expected.material.onSurfaceVariant, preview.material.onSurfaceVariant)
                assertEquals(expected.material.secondaryContainer, preview.material.secondaryContainer)
                assertEquals(expected.material.onSecondaryContainer, preview.material.onSecondaryContainer)
                assertEquals(expected.miuix.primary, preview.miuix.primary)
                assertEquals(expected.miuix.onSecondaryContainer, preview.miuix.onSecondaryContainer)
                assertEquals(assessThemeContrast(expected.material), assessThemeContrast(preview.material))
            }
        }
    }

    @Test
    fun savingSeedSynchronizesAdvancedControlColorButEditingRolesPreservesIt() {
        for (dark in listOf(false, true)) {
            val configuration = ThemePreviewConfiguration(roleOverrides = overrides, syncControlAccentToSeed = true)
            val preview = resolveThemePreviewPalette(seed, dark, PaletteStyle.TonalSpot,
                ColorSpec.SpecVersion.SPEC_2025, Contrast.Default, SeedPaletteStyle.Standard, configuration)
            assertEquals(seed, preview.material.primary)
            val swatch = resolveThemePresetPaletteSwatch(seed, dark, configuration = configuration)
            assertEquals(preview.material.primary, swatch.primary)
            assertEquals(preview.material.tertiary, swatch.tertiary)
            assertEquals(preview.material.primaryContainer, swatch.primaryContainer)
            val rolePreview = resolveThemePreviewPalette(seed, dark, PaletteStyle.TonalSpot,
                ColorSpec.SpecVersion.SPEC_2025, Contrast.Default, SeedPaletteStyle.Standard,
                configuration.copy(syncControlAccentToSeed = false))
            assertEquals(parseMd3CustomColorHex("#8800AA"), rolePreview.material.primary)
        }
    }
}
