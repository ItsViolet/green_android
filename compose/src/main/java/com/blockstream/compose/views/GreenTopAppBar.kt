package com.blockstream.compose.views

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import cafe.adriel.voyager.navigator.LocalNavigator
import com.blockstream.common.data.NavAction
import com.blockstream.common.data.NavData
import com.blockstream.compose.LocalAppBarState
import com.blockstream.compose.components.MenuEntry
import com.blockstream.compose.components.PopupMenu
import com.blockstream.compose.components.PopupState
import com.blockstream.compose.screens.HomeScreen
import com.blockstream.compose.screens.login.LoginScreen
import com.blockstream.compose.screens.overview.WalletOverviewScreen
import com.blockstream.compose.theme.GreenTheme
import com.blockstream.compose.theme.bodySmall
import com.blockstream.compose.theme.titleSmall
import com.blockstream.compose.utils.AppBarState
import com.blockstream.compose.utils.drawableResourceIdOrNull
import com.blockstream.compose.utils.stringResourceId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreenTopAppBar(
    openDrawer: () -> Unit = { }
) {
    val navigator = LocalNavigator.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    val showDrawerNavigationIcon =
        navigator?.lastItemOrNull?.let { it is HomeScreen || it is LoginScreen || it is WalletOverviewScreen }
            ?: false

    val navData by LocalAppBarState.current.data


    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        title = {
            AnimatedVisibility(
                visible = navData.isVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    navData.title?.also {
                        Text(
                            text = stringResourceId(it),
                            maxLines = 1,
                            style = titleSmall,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    navData.subtitle?.also {
                        Text(
                            text = stringResourceId(it),
                            maxLines = 1,
                            style = bodySmall,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        navigationIcon = {
            AnimatedVisibility(
                visible = navData.isVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                AnimatedContent(targetState = showDrawerNavigationIcon, transitionSpec = {
                    slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(400)
                    ).togetherWith(
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(200)
                        )
                    )
                }, label = "NavigationIcon") { showDrawerNavigationIcon ->
                    if (showDrawerNavigationIcon) {
                        IconButton(onClick = {
                            openDrawer()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Drawer Menu"
                            )
                        }
                    } else {
                        IconButton(enabled = navData.isVisible, onClick = {
                            if(navData.isVisible){
                                navigator?.pop()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                }
            }
        },
        actions = {
            val popupState = remember { PopupState() }

            AnimatedVisibility(
                visible = navData.isVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row {
                    navData.actions.filter { !it.isMenuEntry }.forEach {
                        IconButton(onClick = {
                            it.onClick()
                        }) {
                            drawableResourceIdOrNull(it.icon)?.also {
                                Image(
                                    painter = painterResource(id = it),
                                    contentDescription = null
                                )
                            }
                        }
                    }

                    navData.actions.filter { it.isMenuEntry }.takeIf { it.isNotEmpty() }?.map {
                        MenuEntry.from(it)
                    }?.also {
                        IconButton(onClick = {
                            popupState.isContextMenuVisible.value = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Menu"
                            )
                        }

                        PopupMenu(popupState, it)
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )

}

@Preview
@Composable
private fun GreenTopAppScreenPreview() {
    GreenTheme {
        val appBarState = remember {
            AppBarState(
                NavData(
                    title = "Title",
                    subtitle = "Subtitle",
                    actions = listOf(NavAction(title = "Action"))
                )
            )
        }

        appBarState.update(NavData(title = "Title 2"))

        CompositionLocalProvider(LocalAppBarState provides appBarState) {
            Scaffold(topBar = {
                GreenTopAppBar()
            }) {

                Column(modifier = Modifier.padding(it)) {

                }
            }
        }
    }
}
