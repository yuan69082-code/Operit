package com.ai.assistance.operit.ui.main.layout

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.main.NavigationTransitionSource
import com.ai.assistance.operit.ui.main.TopBarTitleContent
import com.ai.assistance.operit.ui.main.navigation.NavigationEntrySpec
import com.ai.assistance.operit.ui.main.navigation.RouteEntry
import com.ai.assistance.operit.ui.main.components.AppContent
import com.ai.assistance.operit.ui.main.screens.Screen
import kotlinx.coroutines.CoroutineScope

/** Phone destinations live in bottom navigation. No drawer or edge-drag interception. */
@Composable
fun PhoneLayout(
        currentRouteEntry: RouteEntry,
        currentScreen: Screen,
        selectedItem: NavItem?,
        isLoading: Boolean,
        navItems: List<NavItem>,
        pluginSidebarEntries: List<NavigationEntrySpec>,
        selectedRouteId: String,
        isNetworkAvailable: Boolean,
        networkType: String,
        drawerWidth: Dp,
        navController: androidx.navigation.NavController,
        scope: CoroutineScope,
        drawerState: androidx.compose.material3.DrawerState,
        showFpsCounter: Boolean,
        enableNavigationAnimation: Boolean,
        navigationTransitionSource: NavigationTransitionSource,
        onScreenChange: (Screen) -> Unit,
        onDrawerItemSelected: (Screen) -> Unit,
        onNavigationEntrySelected: (NavigationEntrySpec) -> Unit,
        navigateToTokenConfig: () -> Unit,
        canGoBack: Boolean,
        onGoBack: () -> Unit,
        isNavigatingBack: Boolean = false,
        topBarActions: @Composable RowScope.() -> Unit = {},
        topBarTitleContent: TopBarTitleContent? = null,
        /** 当前导航栈中仍存活的路由 screenKey（路由级 ViewModelStore 清理依据）。 */
        aliveScreenKeys: Set<String>
) {
    VioShell(
        currentScreen = currentScreen, navItems = navItems,
        pluginEntries = pluginSidebarEntries,
        onOpenScreen = onDrawerItemSelected, onOpenPlugin = onNavigationEntrySelected,
        isNetworkAvailable = isNetworkAvailable, networkType = networkType,
        canGoBack = canGoBack,
    ) { shellBack ->
        val chatNavigation = LocalChatNavigation.current
        val conversation = currentScreen is Screen.AiChat && chatNavigation?.directoryVisible == false
        AppContent(
            currentRouteEntry = currentRouteEntry, currentScreen = currentScreen,
            selectedItem = selectedItem, useTabletLayout = false, isTabletSidebarExpanded = false,
            isLoading = isLoading, navController = navController, scope = scope, drawerState = drawerState,
            showFpsCounter = showFpsCounter, enableNavigationAnimation = enableNavigationAnimation,
            navigationTransitionSource = navigationTransitionSource, onScreenChange = onScreenChange,
            onToggleSidebar = {}, navigateToTokenConfig = navigateToTokenConfig,
            canGoBack = canGoBack || shellBack != null,
            onGoBack = { if (conversation) shellBack?.invoke() else if (canGoBack) onGoBack() else shellBack?.invoke() },
            isNavigatingBack = isNavigatingBack, actions = topBarActions,
            titleContent = topBarTitleContent, aliveScreenKeys = aliveScreenKeys,
            showTopBar = !(currentScreen is Screen.AiChat && chatNavigation?.directoryVisible == true),
        )
    }
}
