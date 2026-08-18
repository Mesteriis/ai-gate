package com.aigate.router.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.aigate.router.R
import com.aigate.router.ui.theme.FrostTop

/**
 * Frost-gate hero — the «ИИ Врата» emblem (glowing crystalline gate with clouds and
 * circuit traces) on a soft frost gradient. Uses the raster brand asset.
 */
@Composable
fun GateHero(modifier: Modifier = Modifier, heightDp: Int = 220) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    0f to FrostTop,
                    0.6f to Color(0xFFEAF2FD),
                    1f to Color(0xFFF6FAFE)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.gate_hero),
            contentDescription = "ИИ Врата",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(6.dp)
        )
    }
}
