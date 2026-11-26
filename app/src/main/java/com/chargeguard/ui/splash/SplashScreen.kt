package com.chargeguard.ui.splash

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.chargeguard.ui.components.ShieldLogo
import com.chargeguard.ui.theme.CgColors
import com.chargeguard.ui.theme.LocalCgPalette
import kotlinx.coroutines.delay

/** Shown while settings and persisted protection state are loaded (real work, not a fake delay). */
@Composable
fun SplashScreen(ready: Boolean, onFinished: () -> Unit) {
    val shownAt = remember { System.currentTimeMillis() }
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(ready) {
        if (ready) {
            val elapsed = System.currentTimeMillis() - shownAt
            if (elapsed < MIN_VISIBLE_MS) delay(MIN_VISIBLE_MS - elapsed)
            finish()
        }
    }
    val t = rememberInfiniteTransition(label = "loading")
    Box(Modifier.fillMaxSize().background(LocalCgPalette.current.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ShieldLogo(84.dp)
            Spacer(Modifier.height(20.dp))
            Text("ChargeGuard", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text("Charger theft protection", style = MaterialTheme.typography.bodyMedium, color = CgColors.TextSecondary)
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    val a by t.animateFloat(
                        0.2f, 1f,
                        infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                        label = "dot$i",
                    )
                    Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(CgColors.Green))
                }
            }
        }
    }
}

private const val MIN_VISIBLE_MS = 700L
