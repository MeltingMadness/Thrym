package com.thrym.app

import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.ThemeMode
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.ReminderScheduler
import com.thrym.app.presentation.navigation.BottomNavBar
import com.thrym.app.presentation.navigation.ThrymNavHost
import com.thrym.app.presentation.navigation.Screen
import com.thrym.app.presentation.onboarding.OnboardingGate
import com.thrym.app.domain.util.AppLogger
import kotlinx.coroutines.CancellationException
import com.thrym.app.presentation.theme.ThrymTheme
import org.koin.compose.koinInject
import com.thrym.app.domain.model.AppearanceStyle
import com.thrym.app.presentation.theme.LiquidGlassHost
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {

    // Keeps the splash screen visible until the theme preferences are loaded,
    // avoiding a white flash between the dark splash theme and the dark Compose UI.
    private val splashKeepOnScreen = mutableStateOf(true)

    // Read by the system bar style on every configuration change that does not recreate the
    // activity (activity 1.13 re-applies edge-to-edge then), so it has to follow the current theme.
    private var systemBarsDark = true

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { splashKeepOnScreen.value }
        super.onCreate(savedInstanceState)

        val systemInDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // Preferences load asynchronously; until then the app-default theme mode (DARK) applies,
        // so the launch theme is dark regardless of the system setting.
        val launchThemeMode = ThemeMode.DARK
        systemBarsDark = when (launchThemeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> systemInDarkMode
        }
        val transparentScrim = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(transparentScrim, transparentScrim) { systemBarsDark },
            navigationBarStyle = SystemBarStyle.auto(transparentScrim, transparentScrim) { systemBarsDark }
        )
        setContent {
            val preferencesRepository: AppPreferencesRepository = koinInject()
            val reminderScheduler: ReminderScheduler = koinInject()
            val preferencesState by preferencesRepository.preferences.collectAsStateWithLifecycle(
                initialValue = null
            )

            // Decided once per activity: a fresh install starts with the onboarding, everyone
            // else on the dashboard. Saved so a configuration change keeps the same graph.
            val onboardingGate: OnboardingGate = koinInject()
            var startDestination by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                if (startDestination == null) {
                    val showOnboarding = try {
                        onboardingGate.shouldShow()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w("MainActivity", "Onboarding-Status nicht lesbar: ${e.message}", e)
                        false
                    }
                    startDestination = if (showOnboarding) Screen.Onboarding.route else Screen.Dashboard.route
                }
            }

            splashKeepOnScreen.value = preferencesState == null || startDestination == null

            val preferences = preferencesState ?: return@setContent
            val resolvedStartDestination = startDestination ?: return@setContent

            val isDarkTheme = when (preferences.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            ThrymTheme(
                themeMode = preferences.themeMode,
                themeScheme = preferences.themeScheme,
                useDynamicColor = preferences.useDynamicColor,
                reducedMotion = preferences.reducedMotion,
                appearanceStyle = preferences.appearanceStyle
            ) {
                LaunchedEffect(isDarkTheme) {
                    systemBarsDark = isDarkTheme
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                    insetsController.isAppearanceLightStatusBars = !isDarkTheme
                    insetsController.isAppearanceLightNavigationBars = !isDarkTheme
                }

                LaunchedEffect(preferences.timerKeepScreenOn) {
                    if (preferences.timerKeepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                LaunchedEffect(preferences.reminderConfig) {
                    reminderScheduler.sync(preferences.reminderConfig)
                }

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val showBottomBar = currentRoute in listOf(
                    Screen.Dashboard.route,
                    Screen.WorkoutHistory.route,
                    Screen.ExerciseLibrary.route,
                    Screen.TrainingPlanList.route
                )
                val appShell: @Composable () -> Unit = {
                    androidx.compose.material3.Scaffold(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        bottomBar = {
                            if (showBottomBar) {
                                BottomNavBar(navController)
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .consumeWindowInsets(innerPadding)
                        ) {
                            ThrymNavHost(navController, startDestination = resolvedStartDestination)
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    if (preferences.appearanceStyle == AppearanceStyle.LIQUID_GLASS) {
                        LiquidGlassHost(modifier = Modifier.fillMaxSize()) { appShell() }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                                            MaterialTheme.colorScheme.background,
                                            MaterialTheme.colorScheme.background
                                        )
                                    )
                                )
                        ) { appShell() }
                    }
                }
            }
        }
    }
}
