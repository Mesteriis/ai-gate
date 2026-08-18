package com.aigate.router.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.aigate.router.ui.theme.FrostGlow
import com.aigate.router.ui.theme.FrostTop
import com.aigate.router.ui.theme.Primary
import com.aigate.router.ui.theme.Secondary

/**
 * Frost-gate hero — the «ИИ Врата» motif drawn as vector art: a frosty sky with soft
 * clouds and a snow-capped mountain ridge, an open crystalline gate radiating a
 * cyan-white light beam, and glowing circuit traces branching to network nodes.
 * Vector (Canvas) so it stays crisp on the Fold's large screen and adapts to theme.
 */
@Composable
fun GateHero(modifier: Modifier = Modifier, heightDp: Int = 200) {
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "glowA"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(RoundedCornerShape(20.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(heightDp.dp)) {
            val w = size.width
            val h = size.height

            // ── Sky (frost gradient) ──
            drawRect(
                brush = Brush.verticalGradient(
                    0f to FrostTop,
                    0.55f to Color(0xFFE9F2FD),
                    1f to Color(0xFFF4F8FE)
                )
            )

            // ── Soft clouds ──
            val cloud = Color.White.copy(alpha = 0.55f)
            drawCloud(Offset(w * 0.18f, h * 0.30f), w * 0.16f, cloud)
            drawCloud(Offset(w * 0.80f, h * 0.22f), w * 0.18f, cloud)
            drawCloud(Offset(w * 0.62f, h * 0.40f), w * 0.12f, Color.White.copy(alpha = 0.4f))

            // ── Mountain ridge with snow caps ──
            val ridge = Path().apply {
                moveTo(0f, h)
                lineTo(0f, h * 0.80f)
                lineTo(w * 0.16f, h * 0.62f)
                lineTo(w * 0.30f, h * 0.78f)
                lineTo(w * 0.46f, h * 0.55f)
                lineTo(w * 0.60f, h * 0.76f)
                lineTo(w * 0.78f, h * 0.60f)
                lineTo(w, h * 0.80f)
                lineTo(w, h)
                close()
            }
            drawPath(ridge, brush = Brush.verticalGradient(
                0f to Color(0xFFBCD2EE), 1f to Color(0xFFD8E6F7)
            ))
            // snow caps
            val snow = Color.White.copy(alpha = 0.85f)
            drawSnowCap(Offset(w * 0.46f, h * 0.55f), w * 0.05f, h * 0.07f, snow)
            drawSnowCap(Offset(w * 0.16f, h * 0.62f), w * 0.04f, h * 0.055f, snow)
            drawSnowCap(Offset(w * 0.78f, h * 0.60f), w * 0.04f, h * 0.06f, snow)

            // ── Circuit traces + nodes ──
            val trace = Secondary.copy(alpha = 0.55f * glow)
            drawCircuit(w, h, left = true, trace)
            drawCircuit(w, h, left = false, trace)

            // ── The gate ──
            val cx = w / 2f
            val gw = w * 0.16f          // half-width of the gate
            val gTop = h * 0.16f
            val gBottom = h * 0.86f
            val apexY = h * 0.06f

            // central light beam (behind the doors)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Secondary.copy(alpha = 0.9f * glow),
                    0.5f to Color.White.copy(alpha = 0.95f * glow),
                    1f to Secondary.copy(alpha = 0.6f * glow)
                ),
                topLeft = Offset(cx - gw * 0.14f, apexY),
                size = androidx.compose.ui.geometry.Size(gw * 0.28f, gBottom - apexY)
            )
            // glow halo
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.8f * glow),
                    0.4f to Secondary.copy(alpha = 0.35f * glow),
                    1f to Color.Transparent
                ),
                radius = gw * 1.5f,
                center = Offset(cx, (gTop + gBottom) / 2f)
            )

            // outer ogive frame (pointed-arch portal around the doors)
            val frameApexY = apexY - h * 0.03f
            val frame = Path().apply {
                moveTo(cx - gw * 1.14f, gBottom)
                lineTo(cx - gw * 1.14f, gTop)
                quadraticBezierTo(cx - gw * 1.14f, frameApexY, cx, frameApexY)
                quadraticBezierTo(cx + gw * 1.14f, frameApexY, cx + gw * 1.14f, gTop)
                lineTo(cx + gw * 1.14f, gBottom)
            }
            drawPath(frame, color = Color.White.copy(alpha = 0.9f), style = Stroke(width = h * 0.03f))
            drawPath(frame, color = Primary.copy(alpha = 0.55f), style = Stroke(width = h * 0.012f))

            // left & right door leaves — pointed (gothic) arch meeting at a sharp apex
            val doorStroke = Stroke(width = h * 0.018f)
            val leafColor = Color.White
            val leftLeaf = Path().apply {
                moveTo(cx - gw, gBottom)
                lineTo(cx - gw, gTop)
                quadraticBezierTo(cx - gw, apexY, cx - gw * 0.06f, apexY)
                lineTo(cx - gw * 0.06f, gBottom)
                close()
            }
            val rightLeaf = Path().apply {
                moveTo(cx + gw, gBottom)
                lineTo(cx + gw, gTop)
                quadraticBezierTo(cx + gw, apexY, cx + gw * 0.06f, apexY)
                lineTo(cx + gw * 0.06f, gBottom)
                close()
            }
            drawPath(leftLeaf, color = leafColor.copy(alpha = 0.94f))
            drawPath(rightLeaf, color = leafColor.copy(alpha = 0.94f))
            drawPath(leftLeaf, color = Primary.copy(alpha = 0.5f), style = doorStroke)
            drawPath(rightLeaf, color = Primary.copy(alpha = 0.5f), style = doorStroke)

            // rune/engraving lines on each leaf
            val eng = Primary.copy(alpha = 0.30f)
            for (i in 1..4) {
                val y = gTop + (gBottom - gTop) * (i / 5f)
                drawLine(eng, Offset(cx - gw * 0.85f, y), Offset(cx - gw * 0.2f, y), strokeWidth = h * 0.006f)
                drawLine(eng, Offset(cx + gw * 0.2f, y), Offset(cx + gw * 0.85f, y), strokeWidth = h * 0.006f)
            }

            // glowing apex diamond
            val d = gw * 0.22f
            val diamond = Path().apply {
                moveTo(cx, apexY - d)
                lineTo(cx + d * 0.7f, apexY)
                lineTo(cx, apexY + d)
                lineTo(cx - d * 0.7f, apexY)
                close()
            }
            drawPath(diamond, color = Secondary.copy(alpha = glow))
            drawPath(diamond, color = Color.White.copy(alpha = 0.9f * glow), style = Stroke(width = h * 0.008f))
        }
    }
}

private fun DrawScope.drawCloud(center: Offset, r: Float, color: Color) {
    drawCircle(color, r * 0.9f, center)
    drawCircle(color, r * 0.7f, Offset(center.x - r * 0.9f, center.y + r * 0.15f))
    drawCircle(color, r * 0.75f, Offset(center.x + r * 0.95f, center.y + r * 0.1f))
    drawCircle(color, r * 0.6f, Offset(center.x + r * 0.4f, center.y - r * 0.4f))
}

private fun DrawScope.drawSnowCap(peak: Offset, halfW: Float, capH: Float, color: Color) {
    val p = Path().apply {
        moveTo(peak.x, peak.y)
        lineTo(peak.x + halfW, peak.y + capH)
        lineTo(peak.x + halfW * 0.3f, peak.y + capH * 0.7f)
        lineTo(peak.x, peak.y + capH)
        lineTo(peak.x - halfW * 0.3f, peak.y + capH * 0.7f)
        lineTo(peak.x - halfW, peak.y + capH)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.drawCircuit(w: Float, h: Float, left: Boolean, color: Color) {
    val sign = if (left) -1f else 1f
    val startX = w / 2f + sign * w * 0.18f
    val y0 = h * 0.42f
    val sw = h * 0.008f
    val p = Path().apply {
        moveTo(startX, y0)
        lineTo(startX + sign * w * 0.10f, y0)
        lineTo(startX + sign * w * 0.10f, y0 - h * 0.12f)
        lineTo(startX + sign * w * 0.22f, y0 - h * 0.12f)
    }
    drawPath(p, color, style = Stroke(width = sw))
    drawCircle(color, h * 0.014f, Offset(startX + sign * w * 0.22f, y0 - h * 0.12f))
    val p2 = Path().apply {
        moveTo(startX, y0 + h * 0.10f)
        lineTo(startX + sign * w * 0.16f, y0 + h * 0.10f)
        lineTo(startX + sign * w * 0.16f, y0 + h * 0.24f)
    }
    drawPath(p2, color, style = Stroke(width = sw))
    drawCircle(color, h * 0.012f, Offset(startX + sign * w * 0.16f, y0 + h * 0.24f))
}
