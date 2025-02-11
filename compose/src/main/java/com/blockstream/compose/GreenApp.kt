package com.blockstream.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.ScreenTransition
import cafe.adriel.voyager.transitions.ScreenTransitionContent
import com.blockstream.common.managers.LifecycleManager
import com.blockstream.common.models.drawer.DrawerViewModel
import com.blockstream.compose.screens.DrawerScreen
import com.blockstream.compose.screens.HomeScreen
import com.blockstream.compose.screens.LockScreen
import com.blockstream.compose.sheets.BottomSheetNavigatorM3
import com.blockstream.compose.sideeffects.DialogHost
import com.blockstream.compose.sideeffects.DialogState
import com.blockstream.compose.theme.GreenTheme
import com.blockstream.compose.utils.AppBarState
import com.blockstream.compose.views.GreenTopAppBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject


val LocalAppBarState = compositionLocalOf { AppBarState() }
val LocalSnackbar = compositionLocalOf { SnackbarHostState() }
val LocalAppCoroutine = compositionLocalOf { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
val LocalDrawer = compositionLocalOf { DrawerState(DrawerValue.Closed) }
val LocalDialog: ProvidableCompositionLocal<DialogState> = staticCompositionLocalOf { error("DialogState not initialized") }

@Composable
fun GreenApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val appBarState = remember { AppBarState() }
    val dialogState = remember { DialogState(context = context)}

    CompositionLocalProvider(
        LocalSnackbar provides snackbarHostState,
        LocalAppBarState provides appBarState,
        LocalDrawer provides drawerState,
        LocalDialog provides dialogState,
    ) {

        var navigator: Navigator? = null

        val lifecycleManager = koinInject<LifecycleManager>()
        val isLocked by lifecycleManager.isLocked.collectAsStateWithLifecycle()

        Box {
            BottomSheetNavigatorM3 { _ ->

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = MaterialTheme.colorScheme.background,
                        ) {
                            val drawerViewModel = koinViewModel<DrawerViewModel>()

                            CompositionLocalProvider(
                                LocalNavigator provides navigator,
                            ) {
                                DrawerScreen(viewModel = drawerViewModel)
                            }
                        }
                    }
                ) {
                    val localAppBarState = LocalAppBarState.current
                    Navigator(screen = HomeScreen, onBackPressed = { _ ->
                        !isLocked && localAppBarState.data.value.isVisible && localAppBarState.data.value.onBackPressed()
                    }) {
                        navigator = it
                        Scaffold(
                            snackbarHost = {
                                SnackbarHost(hostState = snackbarHostState)
                            },
                            topBar = {
                                GreenTopAppBar(
                                    openDrawer = {
                                        scope.launch {
                                            // Open the drawer with animation
                                            // and suspend until it is fully
                                            // opened or animation has been canceled
                                            drawerState.open()
                                        }
                                    }
                                )
                            },
                            content = { innerPadding ->
                                Box(
                                    modifier = Modifier
                                        .padding(innerPadding),
                                ) {
                                    FadeSlideTransition(it)
                                }
                            },
                        )
                    }

                    DialogHost(state = dialogState)
                }
            }

            AnimatedVisibility(
                visible = isLocked,
                enter = EnterTransition.None,
                exit = fadeOut()
            ) {
                LockScreen {
                    lifecycleManager.unlock()
                }
            }
        }
    }
}

@Composable
fun AppFragmentBridge(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dialogState = remember { DialogState(context = context)}

    GreenTheme {
        CompositionLocalProvider(
            LocalDialog provides dialogState,
        ) {
            BottomSheetNavigatorM3 {
                DialogHost(state = dialogState)
                content()
            }
        }
    }
}

@Composable
fun GreenPreview(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dialogState = remember { DialogState(context = context)}

    GreenTheme {
        CompositionLocalProvider(
            LocalDialog provides dialogState,
        ) {
            BottomSheetNavigatorM3 {
                DialogHost(state = dialogState)
                content()
            }
        }
    }
}

@Composable
public fun FadeSlideTransition(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    content: ScreenTransitionContent = { it.Content() }
) {
    ScreenTransition(
        navigator = navigator,
        modifier = modifier,
        content = content,
        transition = {
            val (initialOffset, targetOffset) = when (navigator.lastEvent) {
                StackEvent.Pop -> ({ size: Int -> -size }) to ({ size: Int -> size })
                else -> ({ size: Int -> size }) to ({ size: Int -> -size })
            }

            val animationSpec: FiniteAnimationSpec<IntOffset> = spring(
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = IntOffset.VisibilityThreshold
            )

            val animationSpecFade: FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow)

            if(navigator.lastEvent == StackEvent.Pop){
                fadeIn(animationSpecFade) togetherWith slideOutHorizontally(animationSpec, targetOffset)
            }else{
                slideInHorizontally(animationSpec, initialOffset) togetherWith fadeOut(animationSpec = animationSpecFade)
            }
        }
    )
}
