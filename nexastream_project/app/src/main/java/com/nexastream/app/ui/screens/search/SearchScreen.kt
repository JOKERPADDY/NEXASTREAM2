package com.nexastream.app.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow
import com.nexastream.app.ui.components.MoviePoster

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onMovieClick: (String) -> Unit,
    onGenreClick: (String, String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    var query by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(top = 16.dp)
    ) {
        TextField(
            value = query,
            onValueChange = { 
                query = it
                viewModel.search(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("Search or ask AI (e.g., '90s sci-fi movies')...", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.DarkGray.copy(alpha = 0.5f),
                unfocusedContainerColor = Color.DarkGray.copy(alpha = 0.5f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Color.Red,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "✨ AI Natural Search",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = uiState.isAiSearchEnabled,
                onCheckedChange = { viewModel.toggleAiSearch() }
            )
        }

        if (uiState.aiExplanation.isNotBlank() && uiState.isAiSearchEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color(0xFFD76D77), Color(0xFF3A1C71))
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = uiState.aiExplanation,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (query.isEmpty()) {
            Text(
                text = "✨ AI Discovery Prompts",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.aiPrompts) { prompt ->
                    AssistChip(
                        onClick = {
                            query = prompt.query
                            viewModel.search(prompt.query)
                        },
                        label = { Text("${prompt.emoji} ${prompt.title}") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color.DarkGray.copy(alpha = 0.7f),
                            labelColor = Color.White
                        )
                    )
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(uiState.results) { item ->
                when (item) {
                    is Movie -> {
                        MoviePoster(
                            posterUrl = item.poster,
                            onClick = { onMovieClick(item.id) }
                        )
                    }
                    is TvShow -> {
                        MoviePoster(
                            posterUrl = item.poster,
                            onClick = { onMovieClick(item.id) }
                        )
                    }
                    is Show -> {
                        MoviePoster(
                            posterUrl = item.poster,
                            onClick = { onMovieClick(item.id) }
                        )
                    }
                    is Genre -> {
                        GenreCard(
                            genre = item,
                            onClick = { onGenreClick(item.id, item.name) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GenreCard(
    genre: Genre,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = Color.DarkGray.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = genre.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
