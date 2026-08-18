package com.aigate.router.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.aigate.router.R

/**
 * Frost-gate hero — the «ИИ Врата» emblem (glowing crystalline gate with clouds and
 * circuit traces). Rendered as the transparent brand asset directly on the page
 * background — no card/plate behind it.
 */
@Composable
fun GateHero(modifier: Modifier = Modifier, heightDp: Int = 220) {
    Image(
        painter = painterResource(R.drawable.gate_hero),
        contentDescription = "ИИ Врата",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
    )
}
