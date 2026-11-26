package com.chargeguard.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chargeguard.ui.components.IconBadge
import com.chargeguard.ui.components.PrimaryButton
import com.chargeguard.ui.components.SecondaryButton
import com.chargeguard.ui.components.ShieldLogo
import com.chargeguard.ui.theme.CgColors
import com.chargeguard.ui.theme.LocalCgPalette
import kotlinx.coroutines.launch

private data class Page(val icon: ImageVector?, val tint: Color, val title: String, val body: String, val bullets: List<String> = emptyList())

private val pages = listOf(
    Page(
        null, CgColors.Green, "Protect your phone while it charges",
        "ChargeGuard watches the charging connection. If someone unplugs your phone while protection is on, a loud alarm sounds until the charger is plugged back in.",
    ),
    Page(
        Icons.Rounded.Cable, CgColors.Green, "How it works",
        "Four simple steps:",
        listOf(
            "Connect your charger and tap the shield to activate.",
            "Lock your phone. Monitoring keeps running.",
            "If external power is removed, the siren, vibration and alarm screen start.",
            "Reconnect the charger: the alarm stops and protection re-arms.",
        ),
    ),
    Page(
        Icons.Rounded.NotificationsActive, CgColors.Amber, "Why notifications",
        "Android requires a visible notification while an app monitors in the background. ChargeGuard also uses a high-priority notification to show the alarm on your lock screen.",
        listOf(
            "Without notification permission the siren still works.",
            "You won't see the monitoring status or the lock-screen alarm.",
            "You can change this anytime in Settings.",
        ),
    ),
    Page(
        Icons.Rounded.Sync, CgColors.Green, "Why foreground monitoring",
        "Protection runs as a foreground service with a persistent notification. That is the only reliable way Android lets an app keep listening to charger events while the screen is off.",
        listOf(
            "Monitoring only starts when you activate it.",
            "It stops as soon as you turn protection off.",
            "No internet, no account, no tracking.",
        ),
    ),
    Page(
        Icons.Rounded.Info, CgColors.Red, "What ChargeGuard can't do",
        "Be aware of Android's limits. ChargeGuard will always tell you when it is not protecting you.",
        listOf(
            "It can't stop someone holding the power button to turn the phone off.",
            "It can't survive Force stop, a reboot or aggressive manufacturer battery savers. You'll need to turn it on again.",
            "A thief with your unlocked phone could turn it off. Lock your phone and consider an owner PIN.",
            "Volume can be lowered with system controls. The alarm uses your phone's alarm volume.",
        ),
    ),
)

@Composable
fun OnboardingRoute(onFinished: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        vm.complete(onFinished)
    }
    OnboardingScreen(
        onFinish = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else vm.complete(onFinished)
        },
        onSkipPermission = { vm.complete(onFinished) },
    )
}

@Composable
fun OnboardingScreen(onFinish: () -> Unit, onSkipPermission: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    Column(
        Modifier
            .fillMaxSize()
            .background(LocalCgPalette.current.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ShieldLogo(24.dp)
            Spacer(Modifier.width(8.dp))
            Text("ChargeGuard", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!last) {
                TextButton(onClick = { scope.launch { pager.animateScrollToPage(pages.lastIndex) } }) {
                    Text("Skip", color = CgColors.TextSecondary)
                }
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index -> PageContent(pages[index]) }

        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                val selected = i == pager.currentPage
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(width = if (selected) 22.dp else 7.dp, height = 7.dp)
                        .clip(CircleShape)
                        .background(if (selected) CgColors.Green else LocalCgPalette.current.elevated)
                )
            }
        }
        if (last) {
            PrimaryButton(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "Continue and allow notifications" else "Get started",
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                TextButton(onClick = onSkipPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue without notifications", color = CgColors.TextSecondary)
                }
            }
        } else {
            SecondaryButton("Next", onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PageContent(page: Page) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (page.icon == null) ShieldLogo(72.dp) else IconBadge(page.icon, page.tint, size = 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(page.title, style = MaterialTheme.typography.headlineMedium)
        Text(page.body, style = MaterialTheme.typography.bodyLarge, color = CgColors.TextSecondary)
        page.bullets.forEachIndexed { i, b ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(page.tint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = page.tint) }
                Spacer(Modifier.width(12.dp))
                Text(b, style = MaterialTheme.typography.bodyMedium, color = CgColors.TextPrimary)
            }
        }
        if (page.icon == null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Shield, CgColors.Green, size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Text("Works offline. No account. No ads. No tracking.", style = MaterialTheme.typography.bodyMedium, color = CgColors.TextSecondary)
            }
        }
    }
}
