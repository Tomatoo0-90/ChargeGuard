package com.chargeguard.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.chargeguard.domain.model.AppSettings
import com.chargeguard.ui.history.HistoryRoute
import com.chargeguard.ui.home.HomeRoute
import com.chargeguard.ui.onboarding.OnboardingRoute
import com.chargeguard.ui.settings.AlarmSettingsRoute
import com.chargeguard.ui.settings.SettingsRoute
import com.chargeguard.ui.splash.SplashScreen

object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ALARM_SETTINGS = "alarm_settings"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
}

@Composable
fun AppNavHost(nav: NavHostController, settings: AppSettings?, ready: Boolean) {
    NavHost(
        navController = nav,
        startDestination = Routes.SPLASH,
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) },
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(ready = ready && settings != null) {
                val next = if (settings?.onboardingCompleted == true) Routes.HOME else Routes.ONBOARDING
                nav.navigate(next) { popUpTo(Routes.SPLASH) { inclusive = true } }
            }
        }
        composable(Routes.ONBOARDING) {
            OnboardingRoute(onFinished = {
                nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
        composable(Routes.HOME) {
            HomeRoute(
                onOpenAlarmSettings = { nav.navigate(Routes.ALARM_SETTINGS) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenHistory = { nav.navigate(Routes.HISTORY) { launchSingleTop = true } },
                onOpenPermissions = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            )
        }
        composable(Routes.ALARM_SETTINGS) { AlarmSettingsRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) {
            SettingsRoute(onBack = { nav.popBackStack() }, onOpenAlarmSettings = { nav.navigate(Routes.ALARM_SETTINGS) { launchSingleTop = true } })
        }
        composable(Routes.HISTORY) { HistoryRoute(onBack = { nav.popBackStack() }) }
    }
}
