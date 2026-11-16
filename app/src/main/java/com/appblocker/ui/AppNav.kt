package com.appblocker.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private const val ROUTE_LIST = "list"
// blockId is optional: no id means "new block".
private const val ROUTE_EDITOR = "editor?blockId={blockId}"

@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_LIST) {
        composable(ROUTE_LIST) {
            BlockListScreen(
                onOpenBlock = { id ->
                    navController.navigate(if (id == null) "editor" else "editor?blockId=$id")
                },
            )
        }
        composable(
            route = ROUTE_EDITOR,
            arguments = listOf(
                navArgument("blockId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            // The ViewModel reads "blockId" from the saved state handle that navigation fills in.
            BlockEditorScreen(onClose = { navController.popBackStack() })
        }
    }
}
