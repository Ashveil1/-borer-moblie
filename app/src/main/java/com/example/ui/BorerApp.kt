package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.BorerApplication
import com.example.data.SessionEntity
import com.example.data.SessionFiles
import com.example.ui.screens.ConsoleScreen
import com.example.ui.screens.IntelScreen
import com.example.ui.screens.LabScreen
import com.example.ui.screens.SectionHeader
import com.example.ui.screens.SessionsScreen
import com.example.ui.screens.SettingsScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object ConsoleRoute

@Serializable
object SessionsRoute

@Serializable
object IntelRoute

@Serializable
object LabRoute

@Serializable
object SettingsRoute

private data class DrawerNavItem(
    val route: Any,
    val label: String,
    val icon: ImageVector,
    val tag: String
)

private val drawerNavItems = listOf(
    DrawerNavItem(ConsoleRoute, "Borer Chat", Icons.Outlined.ChatBubbleOutline, "nav_console"),
    DrawerNavItem(SessionsRoute, "Sessions & Hunt", Icons.Outlined.History, "nav_sessions"),
    DrawerNavItem(IntelRoute, "Intel & Reports", Icons.Outlined.FolderOpen, "nav_intel"),
    DrawerNavItem(LabRoute, "Security Lab", Icons.Outlined.Biotech, "nav_lab"),
    DrawerNavItem(SettingsRoute, "Settings", Icons.Outlined.Settings, "nav_settings")
)

@Composable
fun BorerApp(
    widthSizeClass: WindowWidthSizeClass,
    vm: BorerViewModel? = null
) {
    val app = LocalContext.current.applicationContext as BorerApplication
    val resolvedVm: BorerViewModel = vm ?: viewModel(
        factory = remember(app) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = BorerViewModel(app) as T
            }
        }
    )
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val isExpanded = widthSizeClass == WindowWidthSizeClass.Expanded

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val activeSessions by resolvedVm.activeSessions.collectAsStateWithLifecycle()
    val activeRunId by resolvedVm.activeRunId.collectAsStateWithLifecycle()
    val consoleState by resolvedVm.console.collectAsStateWithLifecycle()

    fun navigateTo(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        if (!isExpanded) {
            scope.launch { drawerState.close() }
        }
    }

    val openDrawer: (() -> Unit)? = if (isExpanded) null else {
        { scope.launch { drawerState.open() } }
    }

    val drawerBody: @Composable () -> Unit = {
        BorerDrawerContent(
            currentDestIsConsole = currentDest?.hasRoute(ConsoleRoute::class) == true,
            currentDestIsSessions = currentDest?.hasRoute(SessionsRoute::class) == true,
            currentDestIsIntel = currentDest?.hasRoute(IntelRoute::class) == true,
            currentDestIsLab = currentDest?.hasRoute(LabRoute::class) == true,
            currentDestIsSettings = currentDest?.hasRoute(SettingsRoute::class) == true,
            sessions = activeSessions,
            activeRunId = activeRunId,
            consoleState = consoleState,
            onNavigate = ::navigateTo,
            onNewChat = {
                resolvedVm.startNewSession()
                navigateTo(ConsoleRoute)
            },
            onSelectSession = { session ->
                resolvedVm.resumeSession(session)
                navigateTo(ConsoleRoute)
            }
        )
    }

    val contentHost: @Composable () -> Unit = {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) {
            NavHost(
                navController = navController,
                startDestination = ConsoleRoute,
                modifier = Modifier.fillMaxSize()
            ) {
                composable<ConsoleRoute> {
                    ConsoleScreen(
                        vm = resolvedVm,
                        onOpenDrawer = openDrawer,
                        onOpenSessions = { navigateTo(SessionsRoute) },
                        onOpenIntel = { navigateTo(IntelRoute) },
                        onOpenLab = { navigateTo(LabRoute) },
                        onOpenSettings = { navigateTo(SettingsRoute) }
                    )
                }
                composable<SessionsRoute> {
                    SessionsScreen(
                        vm = resolvedVm,
                        onOpenDrawer = openDrawer,
                        onResumedOrStarted = { navigateTo(ConsoleRoute) }
                    )
                }
                composable<IntelRoute> {
                    IntelScreen(
                        vm = resolvedVm,
                        onOpenDrawer = openDrawer
                    )
                }
                composable<LabRoute> {
                    LabScreen(
                        vm = resolvedVm,
                        onOpenDrawer = openDrawer,
                        onOpenSettings = { navigateTo(SettingsRoute) }
                    )
                }
                composable<SettingsRoute> {
                    SettingsScreen(
                        vm = resolvedVm,
                        onOpenDrawer = openDrawer
                    )
                }
            }
        }
    }

    if (isExpanded) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.width(284.dp)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            drawerBody()
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            },
            content = contentHost
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.width(304.dp)
                ) {
                    drawerBody()
                }
            },
            content = contentHost
        )
    }
}

@Composable
private fun BorerDrawerContent(
    currentDestIsConsole: Boolean,
    currentDestIsSessions: Boolean,
    currentDestIsIntel: Boolean,
    currentDestIsLab: Boolean,
    currentDestIsSettings: Boolean,
    sessions: List<SessionEntity>,
    activeRunId: String,
    consoleState: ConsoleUiState,
    onNavigate: (Any) -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (SessionEntity) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val searchBorder by animateColorAsState(
        targetValue = if (searchFocused) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outline,
        animationSpec = tween(150),
        label = "drawer_search_border"
    )
    val filteredSessions = remember(sessions, search) {
        if (search.isBlank()) sessions.take(25)
        else sessions.filter {
            it.title.contains(search, ignoreCase = true) ||
                it.model.contains(search, ignoreCase = true)
        }.take(25)
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Search + New Chat row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, searchBorder, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search chats",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.weight(1f)
                ) {
                    if (search.isEmpty()) {
                        Text(
                            text = "Search chats",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = search,
                        onValueChange = { search = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { searchFocused = it.isFocused }
                    )
                }
                if (search.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable { search = "" }
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            ) {
                IconButton(
                    onClick = onNewChat,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("drawer_new_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.EditNote,
                        contentDescription = "New chat",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Workspace navigation links
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            drawerNavItems.forEach { item ->
                val selected = when (item.route) {
                    ConsoleRoute -> currentDestIsConsole
                    SessionsRoute -> currentDestIsSessions
                    IntelRoute -> currentDestIsIntel
                    LabRoute -> currentDestIsLab
                    SettingsRoute -> currentDestIsSettings
                    else -> false
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                            else Color.Transparent
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.outlineVariant else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onNavigate(item.route) }
                        .padding(horizontal = 12.dp)
                        .testTag(item.tag)
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))

        SectionHeader(
            title = "Recent",
            trailing = filteredSessions.size.takeIf { it > 0 }?.toString(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (filteredSessions.isEmpty()) {
                item {
                    Text(
                        text = if (search.isBlank()) "No saved sessions yet" else "No matching sessions",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                }
            } else {
                items(filteredSessions, key = { it.id }) { session ->
                    val isActive = session.id == activeRunId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isActive) MaterialTheme.colorScheme.surfaceContainerHigh
                                else Color.Transparent
                            )
                            .border(
                                width = 1.dp,
                                color = if (isActive) MaterialTheme.colorScheme.outline else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectSession(session) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("drawer_session_${session.id}")
                    ) {
                        if (isActive) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                            ),
                            color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = SessionFiles.ageOf(session.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Bottom minimal status card
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                .clickable { onNavigate(SettingsRoute) }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Borer",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .then(
                                if (consoleState.providerConfigured) {
                                    Modifier.background(MaterialTheme.colorScheme.primary)
                                } else {
                                    Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                                }
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (consoleState.providerConfigured) consoleState.activeModel else "Configure provider",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Open Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
