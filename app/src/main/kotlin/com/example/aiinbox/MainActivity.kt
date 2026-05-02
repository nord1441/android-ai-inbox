package com.example.aiinbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.aiinbox.notification.NotificationHelper
import com.example.aiinbox.ui.detail.DetailScreen
import com.example.aiinbox.ui.detail.DetailViewModel
import com.example.aiinbox.ui.inbox.InboxScreen
import com.example.aiinbox.ui.navigation.Routes
import com.example.aiinbox.ui.theme.AiInboxTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openItemId = intent.getStringExtra(NotificationHelper.EXTRA_OPEN_ITEM_ID)
        setContent {
            AiInboxTheme {
                val nav = rememberNavController()
                val startDestination = if (openItemId != null) Routes.detail(openItemId) else Routes.INBOX

                NavHost(navController = nav, startDestination = startDestination) {
                    composable(Routes.INBOX) {
                        InboxScreen(onItemClick = { id -> nav.navigate(Routes.detail(id)) })
                    }
                    composable(
                        route = Routes.DETAIL,
                        arguments = listOf(navArgument(DetailViewModel.NAV_ARG_ID) { type = NavType.StringType }),
                    ) {
                        DetailScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
