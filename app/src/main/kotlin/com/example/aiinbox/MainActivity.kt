package com.example.aiinbox

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.notification.NotificationHelper
import com.example.aiinbox.ui.detail.DetailScreen
import com.example.aiinbox.ui.detail.DetailViewModel
import com.example.aiinbox.ui.inbox.InboxScreen
import com.example.aiinbox.ui.modeldownload.ModelDownloadScreen
import com.example.aiinbox.ui.navigation.Routes
import com.example.aiinbox.ui.settings.SettingsScreen
import com.example.aiinbox.ui.theme.AiInboxTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var modelManager: ModelManager
    @Inject lateinit var driveAuthRepository: com.example.aiinbox.sync.DriveAuthRepository
    @Inject lateinit var syncCoordinator: com.example.aiinbox.sync.SyncCoordinator

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.i("MainActivity", "POST_NOTIFICATIONS granted=$granted")
        // No retry / rationale UI: denial only suppresses progress + completion
        // notifications. Processing itself still works because the FGS keeps
        // running even without a visible notification.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestPostNotifications()
        maybeKickOffSync()
        enrollTombstoneGc()
        val openItemId = intent.getStringExtra(NotificationHelper.EXTRA_OPEN_ITEM_ID)

        // Diagnostic: show what ModelManager is actually checking and finding.
        com.example.aiinbox.llm.ModelVariant.entries
            .filter { it != com.example.aiinbox.llm.ModelVariant.FAKE }
            .forEach { v ->
                val f = modelManager.modelFilePath(v)
                android.util.Log.i(
                    "MainActivity",
                    "Variant=$v path=${f.absolutePath} exists=${f.exists()} length=${if (f.exists()) f.length() else -1}",
                )
            }
        val current = modelManager.currentVariant()
        android.util.Log.i("MainActivity", "currentVariant=$current openItemId=$openItemId")

        setContent {
            AiInboxTheme {
                val nav = rememberNavController()
                val initialRoute = when {
                    current == null -> Routes.MODEL_DOWNLOAD
                    openItemId != null -> Routes.detail(openItemId)
                    else -> Routes.INBOX
                }

                NavHost(navController = nav, startDestination = initialRoute) {
                    composable(Routes.MODEL_DOWNLOAD) {
                        ModelDownloadScreen(onCompleted = {
                            nav.navigate(Routes.INBOX) {
                                popUpTo(Routes.MODEL_DOWNLOAD) { inclusive = true }
                            }
                        })
                    }
                    composable(Routes.INBOX) {
                        InboxScreen(
                            onItemClick = { id -> nav.navigate(Routes.detail(id)) },
                            onSettingsClick = { nav.navigate(Routes.SETTINGS) },
                        )
                    }
                    composable(
                        route = Routes.DETAIL,
                        arguments = listOf(navArgument(DetailViewModel.NAV_ARG_ID) { type = NavType.StringType }),
                    ) {
                        DetailScreen(onBack = { nav.popBackStack() })
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    /**
     * If the user has linked a Drive account, kick off an immediate sync
     * (so a fresh launch pulls anything new) and re-enroll the periodic
     * worker per the persisted interval. The persisted interval lives in
     * the same SharedPreferences that SettingsViewModel writes; we read it
     * directly here to avoid taking a dependency on the ViewModel from
     * an Activity.
     */
    private fun maybeKickOffSync() {
        if (driveAuthRepository.currentEmail() == null) return
        syncCoordinator.requestImmediateSync()
        val prefs = getSharedPreferences("ai_inbox_sync_prefs", MODE_PRIVATE)
        val stored = prefs.getLong("sync_interval_minutes", 30L)
        val interval: Long? = if (stored == -1L) null else stored
        syncCoordinator.setPeriodicInterval(interval)
    }

    /**
     * Daily background sweep that purges tombstones older than 30 days,
     * locally and (best-effort) on Drive. Constraint UNMETERED so we don't
     * waste mobile bandwidth on housekeeping.
     */
    private fun enrollTombstoneGc() {
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "tombstone_gc",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<com.example.aiinbox.work.TombstoneGcWorker>(
                1, java.util.concurrent.TimeUnit.DAYS,
            )
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                        .build()
                )
                .build(),
        )
    }

    private fun maybeRequestPostNotifications() {
        // POST_NOTIFICATIONS is a runtime permission from API 33. Without it,
        // the FGS notification, download progress, and completion notification
        // are all silently dropped — making long-running summaries appear stuck
        // from the user's perspective.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
