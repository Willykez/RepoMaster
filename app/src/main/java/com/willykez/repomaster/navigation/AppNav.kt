package com.willykez.repomaster.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.willykez.repomaster.ui.components.EmptyTabState
import com.willykez.repomaster.ui.screens.branches.BranchesScreen
import com.willykez.repomaster.ui.screens.blame.BlameScreen
import com.willykez.repomaster.ui.screens.changes.ChangesScreen
import com.willykez.repomaster.ui.screens.conflicts.ConflictsScreen
import com.willykez.repomaster.ui.screens.credential.CredentialScreen
import com.willykez.repomaster.ui.screens.diff.DiffScreen
import com.willykez.repomaster.ui.screens.discover.DiscoverScreen
import com.willykez.repomaster.ui.screens.editor.FileEditorScreen
import com.willykez.repomaster.ui.screens.explorer.FileExplorerScreen
import com.willykez.repomaster.ui.screens.gitignore.GitignoreScreen
import com.willykez.repomaster.ui.screens.log.LogScreen
import com.willykez.repomaster.ui.screens.more.MoreScreen
import com.willykez.repomaster.ui.screens.remote.RemoteScreen
import com.willykez.repomaster.ui.screens.repolist.RepoListScreen
import com.willykez.repomaster.ui.screens.settings.SettingsScreen
import com.willykez.repomaster.ui.screens.stash.StashScreen
import com.willykez.repomaster.ui.screens.tags.TagsScreen

object Routes {
    // Home — not a bottom tab anymore. This is the app's start destination;
    // tapping a repo here pushes into the repo-scoped tabs below it.
    const val REPO_LIST  = "repos"

    // Bottom-tab roots, shown only once a repo is open. None of these carry
    // a repoId in the route itself — which repo they act on is shared UI
    // state (see [selectedRepoId] in [RepoMasterApp]), set on the Home screen.
    const val CHANGES_TAB = "changes_tab"
    const val HISTORY_TAB = "history_tab"
    const val MORE        = "more"
    const val FILES_TAB   = "files_tab"

    // Deep / stack screens — unchanged from before, still reached by pushing
    // on top of whichever tab is current, and still hide the bottom bar.
    const val CREDENTIALS = "credentials"
    const val DISCOVER   = "discover"
    const val SETTINGS   = "settings"
    const val BRANCHES   = "branches/{repoId}"
    const val DIFF       = "diff/{repoId}/{encodedPath}/{staged}"
    const val STASH      = "stash/{repoId}"
    const val REMOTE     = "remote/{repoId}"
    const val TAGS       = "tags/{repoId}"
    const val GITIGNORE  = "gitignore/{repoId}"
    const val EXPLORER   = "explorer/{repoId}/{encodedPath}"
    const val EDITOR     = "editor/{repoId}/{encodedPath}?line={line}"
    const val CONFLICTS  = "conflicts/{repoId}"
    const val BLAME      = "blame/{repoId}/{encodedPath}"
    const val ACTIONS    = "actions/{repoId}"
    const val SEARCH     = "search/{repoId}"

    fun branches(id: Long) = "branches/$id"
    fun stash(id: Long)    = "stash/$id"
    fun remote(id: Long)   = "remote/$id"
    fun tags(id: Long)     = "tags/$id"
    fun gitignore(id: Long)= "gitignore/$id"
    fun diff(id: Long, path: String, staged: Boolean) =
        "diff/$id/${java.net.URLEncoder.encode(path, "UTF-8")}/$staged"
    fun explorer(id: Long, path: String = "") =
        "explorer/$id/${java.net.URLEncoder.encode(path.ifBlank { "." }, "UTF-8")}"
    fun editor(id: Long, path: String, line: Int? = null) =
        "editor/$id/${java.net.URLEncoder.encode(path, "UTF-8")}" + (line?.let { "?line=$it" } ?: "")
    fun conflicts(id: Long) = "conflicts/$id"
    fun actions(id: Long) = "actions/$id"
    fun blame(id: Long, path: String) = "blame/$id/${java.net.URLEncoder.encode(path, "UTF-8")}"
    fun search(id: Long) = "search/$id"
}

private data class TabItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TAB_ITEMS = listOf(
    TabItem(Routes.CHANGES_TAB, "Changes", Icons.Filled.Dashboard),
    TabItem(Routes.HISTORY_TAB, "History", Icons.Filled.History),
    TabItem(Routes.MORE, "Tools", Icons.Filled.Build),
    TabItem(Routes.FILES_TAB, "Files", Icons.Filled.Folder),
)

private const val T = 240
private const val TAB_FADE = 160

/** True when both sides of a nav transition are top-level tab roots — i.e. a bottom-nav
 *  tap, not a push/pop onto the stack. Used to pick a cross-fade over a directional slide,
 *  since sibling tabs have no "forward/back" relationship to each other. */
private fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.isTabSwitch(): Boolean {
    val from = initialState.destination.route
    val to = targetState.destination.route
    return TAB_ITEMS.any { it.route == from } && TAB_ITEMS.any { it.route == to }
}

/**
 * The whole app shell. [Routes.REPO_LIST] is Home — a full-screen repo picker with no
 * bottom bar. Tapping a repo there opens it: a persistent bottom [NavigationBar] with
 * Changes / History / Tools / Files appears, all acting on that one repo, until you back
 * out to Home again. Every deep/detail screen (diff, branches, editor, etc.) still pushes
 * on top of whichever tab is current and hides the bottom bar, same as before.
 */
@Composable
fun RepoMasterApp() {
    val nav = rememberNavController()
    var selectedRepoId by rememberSaveable { mutableStateOf<Long?>(null) }

    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TAB_ITEMS.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TAB_ITEMS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            RepoMasterNavHost(
                nav = nav,
                selectedRepoId = selectedRepoId,
                onRepoSelected = { selectedRepoId = it },
            )
        }
    }
}

@Composable
private fun RepoMasterNavHost(
    nav: NavHostController,
    selectedRepoId: Long?,
    onRepoSelected: (Long) -> Unit,
) {
    NavHost(
        navController = nav,
        startDestination = Routes.REPO_LIST,
        enterTransition = {
            if (isTabSwitch()) fadeIn(tween(TAB_FADE))
            else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(T))
        },
        exitTransition = {
            if (isTabSwitch()) fadeOut(tween(TAB_FADE))
            else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(T))
        },
        popEnterTransition = {
            if (isTabSwitch()) fadeIn(tween(TAB_FADE))
            else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(T))
        },
        popExitTransition = {
            if (isTabSwitch()) fadeOut(tween(TAB_FADE))
            else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(T))
        },
    ) {
        composable(Routes.REPO_LIST) {
            RepoListScreen(
                onOpenRepo = { id -> onRepoSelected(id); nav.navigate(Routes.CHANGES_TAB) },
                onOpenCredentials = { nav.navigate(Routes.CREDENTIALS) },
                onOpenDiscover = { nav.navigate(Routes.DISCOVER) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.CHANGES_TAB) {
            val id = selectedRepoId
            if (id == null) {
                EmptyTabState("Pick a repo to see its changes", onGoToRepos = { nav.navigate(Routes.REPO_LIST) })
            } else {
                ChangesScreen(
                    repoId = id,
                    onBack = { nav.navigate(Routes.REPO_LIST) },
                    onOpenConflicts = { nav.navigate(Routes.conflicts(id)) },
                    onOpenActions = { nav.navigate(Routes.actions(id)) },
                    onOpenDiff = { path, staged -> nav.navigate(Routes.diff(id, path, staged)) },
                )
            }
        }

        composable(Routes.HISTORY_TAB) {
            val id = selectedRepoId
            if (id == null) {
                EmptyTabState("Pick a repo to see its history", onGoToRepos = { nav.navigate(Routes.REPO_LIST) })
            } else {
                LogScreen(
                    repoId = id,
                    onBack = { nav.navigate(Routes.REPO_LIST) },
                    onOpenDiff = { repoId, sha -> nav.navigate("diff/$repoId/${java.net.URLEncoder.encode(sha, "UTF-8")}/commit") },
                )
            }
        }

        composable(Routes.MORE) {
            MoreScreen(
                repoId = selectedRepoId,
                onBack = { nav.navigate(Routes.REPO_LIST) },
                onOpenBranches = { selectedRepoId?.let { nav.navigate(Routes.branches(it)) } },
                onOpenStash = { selectedRepoId?.let { nav.navigate(Routes.stash(it)) } },
                onOpenRemote = { selectedRepoId?.let { nav.navigate(Routes.remote(it)) } },
                onOpenTags = { selectedRepoId?.let { nav.navigate(Routes.tags(it)) } },
                onOpenGitignore = { selectedRepoId?.let { nav.navigate(Routes.gitignore(it)) } },
                onOpenConflicts = { selectedRepoId?.let { nav.navigate(Routes.conflicts(it)) } },
                onOpenActions = { selectedRepoId?.let { nav.navigate(Routes.actions(it)) } },
            )
        }

        composable(Routes.FILES_TAB) {
            val id = selectedRepoId
            if (id == null) {
                EmptyTabState("Pick a repo to browse its files", onGoToRepos = { nav.navigate(Routes.REPO_LIST) })
            } else {
                FileExplorerScreen(
                    repoId = id,
                    relativePath = "",
                    onBack = { nav.navigate(Routes.REPO_LIST) },
                    onOpenFolder = { childPath -> nav.navigate(Routes.explorer(id, childPath)) },
                    onOpenFile = { filePath -> nav.navigate(Routes.editor(id, filePath)) },
                    onOpenBlame = { filePath -> nav.navigate(Routes.blame(id, filePath)) },
                    onOpenSearch = { nav.navigate(Routes.search(id)) },
                )
            }
        }

        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.DISCOVER) { DiscoverScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CREDENTIALS) { CredentialScreen(onBack = { nav.popBackStack() }) }

        composable(Routes.BRANCHES, arguments = listOf(navArgument("repoId") { type = NavType.LongType })) { bs ->
            BranchesScreen(repoId = bs.arguments!!.getLong("repoId"), onBack = { nav.popBackStack() })
        }
        composable(Routes.DIFF, arguments = listOf(
            navArgument("repoId") { type = NavType.LongType },
            navArgument("encodedPath") { type = NavType.StringType },
            navArgument("staged") { type = NavType.StringType },
        )) { bs ->
            DiffScreen(
                repoId = bs.arguments!!.getLong("repoId"),
                encodedPath = bs.arguments!!.getString("encodedPath") ?: "",
                stagedOrCommit = bs.arguments!!.getString("staged") ?: "false",
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.STASH, arguments = listOf(navArgument("repoId") { type = NavType.LongType })) { bs ->
            StashScreen(repoId = bs.arguments!!.getLong("repoId"), onBack = { nav.popBackStack() })
        }
        composable(Routes.REMOTE, arguments = listOf(navArgument("repoId") { type = NavType.LongType })) { bs ->
            RemoteScreen(repoId = bs.arguments!!.getLong("repoId"), onBack = { nav.popBackStack() })
        }
        composable(Routes.TAGS, arguments = listOf(navArgument("repoId") { type = NavType.LongType })) { bs ->
            TagsScreen(repoId = bs.arguments!!.getLong("repoId"), onBack = { nav.popBackStack() })
        }
        composable(Routes.GITIGNORE, arguments = listOf(navArgument("repoId") { type = NavType.LongType })) { bs ->
            GitignoreScreen(repoId = bs.arguments!!.getLong("repoId"), onBack = { nav.popBackStack() })
        }
        composable(Routes.EXPLORER, arguments = listOf(
            navArgument("repoId") { type = NavType.LongType },
            navArgument("encodedPath") { type = NavType.StringType },
        )) { bs ->
            val id = bs.arguments!!.getLong("repoId")
            val decoded = java.net.URLDecoder.decode(bs.arguments!!.getString("encodedPath") ?: "", "UTF-8")
            val path = if (decoded == ".") "" else decoded
            FileExplorerScreen(
                repoId = id,
                relativePath = path,
                onBack = { nav.popBackStack() },
                onOpenFolder = { childPath -> nav.navigate(Routes.explorer(id, childPath)) },
                onOpenFile = { filePath -> nav.navigate(Routes.editor(id, filePath)) },
                onOpenBlame = { filePath -> nav.navigate(Routes.blame(id, filePath)) },
                onOpenSearch = { nav.navigate(Routes.search(id)) },
            )
        }
        composable(Routes.EDITOR, arguments = listOf(
            navArgument("repoId") { type = NavType.LongType },
            navArgument("encodedPath") { type = NavType.StringType },
            navArgument("line") { type = NavType.IntType; defaultValue = -1 },
        )) { bs ->
            val path = java.net.URLDecoder.decode(bs.arguments!!.getString("encodedPath") ?: "", "UTF-8")
            val line = bs.arguments!!.getInt("line")
            FileEditorScreen(
                repoId = bs.arguments!!.getLong("repoId"),
                relativePath = path,
                onBack = { nav.popBackStack() },
                initialLine = if (line >= 1) line else null,
            )
        }
        composable(Routes.CONFLICTS, arguments = listOf(navArgument("repoId") { type = NavType.LongType })) { bs ->
            val id = bs.arguments!!.getLong("repoId")
            ConflictsScreen(
                repoId = id,
                onBack = { nav.popBackStack() },
                onEditFile = { path -> nav.navigate(Routes.editor(id, path)) },
            )
        }
        composable(Routes.ACTIONS, arguments = listOf(navArgument("repoId") { type = NavType.LongType })) { bs ->
            val id = bs.arguments!!.getLong("repoId")
            com.willykez.repomaster.ui.screens.actions.ActionsScreen(
                repoId = id,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.BLAME, arguments = listOf(
            navArgument("repoId") { type = NavType.LongType },
            navArgument("encodedPath") { type = NavType.StringType },
        )) { bs ->
            val id = bs.arguments!!.getLong("repoId")
            val path = java.net.URLDecoder.decode(bs.arguments!!.getString("encodedPath") ?: "", "UTF-8")
            BlameScreen(
                repoId = id, path = path,
                onBack = { nav.popBackStack() },
                onOpenCommit = { sha -> nav.navigate("diff/$id/${java.net.URLEncoder.encode(sha, "UTF-8")}/commit") },
            )
        }
        composable(Routes.SEARCH, arguments = listOf(navArgument("repoId") { type = NavType.LongType })) { bs ->
            val id = bs.arguments!!.getLong("repoId")
            com.willykez.repomaster.ui.screens.search.SearchScreen(
                repoId = id,
                onBack = { nav.popBackStack() },
                onOpenResult = { resultRepoId, path, line -> nav.navigate(Routes.editor(resultRepoId, path, line)) },
            )
        }
    }
}
