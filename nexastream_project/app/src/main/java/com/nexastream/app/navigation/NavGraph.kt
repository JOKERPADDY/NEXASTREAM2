package com.nexastream.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nexastream.app.ui.screens.home.HomeScreen
import com.nexastream.app.ui.screens.details.DetailScreen
import com.nexastream.app.ui.screens.downloads.DownloadsScreen
import com.nexastream.app.ui.screens.player.PlayerScreen
import com.nexastream.app.ui.screens.providers.ProvidersScreen
import com.nexastream.app.ui.screens.search.SearchScreen
import com.nexastream.app.ui.screens.genre.GenreScreen
import com.nexastream.app.providers.Provider
import com.nexastream.app.utils.UserPreferences

@Composable
fun NavGraph(navController: NavHostController) {
    val startDestination = if (UserPreferences.currentProvider == null) {
        Screen.Providers.route
    } else {
        Screen.Home.route
    }
    
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Providers.route) {
            ProvidersScreen(
                onProviderSelected = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Providers.route) { inclusive = true }
                    }
                }
            )
        }
        
        composable(Screen.Search.route) {
            SearchScreen(
                onMovieClick = { id ->
                    navController.navigate(Screen.Details.createRoute(id))
                },
                onGenreClick = { id, name ->
                    navController.navigate(Screen.Genre.createRoute(id, name))
                }
            )
        }

        val onViewAllClick: (com.nexastream.app.models.Category) -> Unit = { category ->
            val (genreId, genreName) = when (category.name) {
                "Livestream" -> "cdn_all_channels" to "CDN Live TV"
                "Latest Movies" -> "latest_movies" to category.name
                "All Cinema" -> "all_cinema" to category.name
                "New Season & Episode" -> "new_season_tv" to category.name
                "Top Rated Movies" -> "tmdb_movies_popular" to category.name
                "Top Rated TV Shows" -> "tmdb_tv_popular" to category.name
                "Trending Today" -> "tmdb_movies_popular" to "Trending"
                "Movies", "Sports", "News", "Entertainment", "Series", "Animation", "Comedy" -> category.name to category.name
                else -> {
                    val name = category.name
                    when {
                        name.startsWith("Popular on") -> {
                            val platform = name.substringAfter("on ").trim()
                            val providerId = when (platform) {
                                "Netflix" -> 8
                                "Disney+" -> 337
                                "Hulu" -> 15
                                "HBO" -> 384
                                "Apple TV+" -> 350
                                "Amazon" -> 10
                                else -> 0
                            }
                            if (providerId != 0) "tmdb_watch_provider_movies_$providerId" to name else "" to name
                        }
                        else -> name to name
                    }
                }
            }
            if (genreId.isNotEmpty()) {
                navController.navigate(Screen.Genre.createRoute(genreId, genreName))
            }
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onMovieClick = { id ->
                    navController.navigate(Screen.Details.createRoute(id))
                },
                onViewAllClick = onViewAllClick
            )
        }

        composable(Screen.Movies.route) {
            HomeScreen(
                onMovieClick = { id -> navController.navigate(Screen.Details.createRoute(id)) },
                onViewAllClick = onViewAllClick
            )
        }

        composable(Screen.TvShows.route) {
            HomeScreen(
                onMovieClick = { id -> navController.navigate(Screen.Details.createRoute(id)) },
                onViewAllClick = onViewAllClick
            )
        }

        composable(
            route = Screen.Genre.route,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType }
            )
        ) {
            GenreScreen(
                onBack = { navController.popBackStack() },
                onShowClick = { id ->
                    navController.navigate(Screen.Details.createRoute(id))
                }
            )
        }

        composable(Screen.Downloads.route) {
            if (Provider.supportsDownloads(UserPreferences.currentProvider)) {
                DownloadsScreen(
                    onPlayClick = { download ->
                        navController.navigate(Screen.Player.createRoute(download.id))
                    }
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Downloads.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        }
        
        composable(
            route = Screen.Details.route,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) {
            DetailScreen(
                onPlayClick = { id ->
                    navController.navigate(Screen.Player.createRoute(id))
                },
                onShowClick = { id ->
                    navController.navigate(Screen.Details.createRoute(id))
                }
            )
        }
        
        composable(
            route = Screen.Player.route,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) {
            PlayerScreen(onBack = { navController.popBackStack() })
        }
    }
}
