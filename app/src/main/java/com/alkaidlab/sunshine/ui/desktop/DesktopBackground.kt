package com.alkaidlab.sunshine.ui.desktop

import androidx.compose.foundation.Canvas
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun DesktopBackground(artwork: DesktopArtwork?, color: Color) {
    val cornerShade = remember { DesktopCornerShade() }
    BoxWithConstraints(Modifier.fillMaxSize().background(color)) {
        val side = minOf(maxHeight * .92f, maxWidth * .67f)
        Canvas(Modifier.fillMaxSize()) {
            if (artwork != null) {
                val iconSide = side.toPx()
                val iconLeft = size.width - iconSide - 12.dp.toPx()
                val iconTop = size.height * .52f - iconSide * .5f
                drawImage(
                    artwork.image,
                    dstOffset = IntOffset(iconLeft.roundToInt(), iconTop.roundToInt()),
                    dstSize = IntSize(iconSide.roundToInt(), iconSide.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
            drawRect(cornerShade)
        }
    }
}

/** Continuous elliptical shade: darkest at lower left, clear by ~36% of each axis. */
private class DesktopCornerShade : ShaderBrush() {
    override fun createShader(size: Size): Shader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xd2000000.toInt(), 0x92000000.toInt(), 0x36000000, 0x00000000),
        floatArrayOf(0f, .35f, .72f, 1f),
        Shader.TileMode.CLAMP,
    ).apply {
        setLocalMatrix(Matrix().apply {
            setScale((size.width * .36f).coerceAtLeast(1f), (size.height * .36f).coerceAtLeast(1f))
            postTranslate(0f, size.height)
        })
    }
}
