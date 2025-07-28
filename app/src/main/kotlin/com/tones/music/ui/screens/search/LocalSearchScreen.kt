package com.tones.music.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.tones.music.LocalPlayerConnection
import com.tones.music.R
import com.tones.music.constants.CONTENT_TYPE_LIST
import com.tones.music.constants.ListItemHeight
import com.tones.music.db.entities.Album
import com.tones.music.db.entities.Artist
import com.tones.music.db.entities.Playlist
import com.tones.music.db.entities.Song
import com.tones.music.extensions.toMediaItem
import com.tones.music.extensions.togglePlayPause
import com.tones.music.playback.queues.ListQueue
import com.tones.music.ui.component.*
import com.tones.music.ui.menu.SongMenu
import com.tones.music.viewmodels.LocalFilter
import com.tones.music.viewmodels.DurationFilter
import com.tones.music.viewmodels.YearFilter
import com.tones.music.viewmodels.LocalSearchViewModel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalSearchScreen(
    query: String,
    navController: NavController,
    onDismiss: () -> Unit,
    isFromCache: Boolean = false,
    pureBlack: Boolean,
    viewModel: LocalSearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val searchFilter by viewModel.filter.collectAsState()
    val durationFilter by viewModel.durationFilter.collectAsState()
    val yearFilter by viewModel.yearFilter.collectAsState()
    val result by viewModel.result.collectAsState()

    val lazyListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect {
                keyboardController?.hide()
            }
    }

    LaunchedEffect(query) {
        viewModel.query.value = query
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        // Main filter chips
        ChipsRow(
            chips = listOf(
                LocalFilter.ALL to stringResource(R.string.filter_all),
                LocalFilter.SONG to stringResource(R.string.filter_songs),
                LocalFilter.ALBUM to stringResource(R.string.filter_albums),
                LocalFilter.ARTIST to stringResource(R.string.filter_artists),
                LocalFilter.PLAYLIST to stringResource(R.string.filter_playlists),
            ),
            currentValue = searchFilter,
            onValueUpdate = { viewModel.filter.value = it },
        )
        
        // Duration filter chips (only show when filtering songs)
        if (searchFilter == LocalFilter.SONG || searchFilter == LocalFilter.ALL) {
            ChipsRow<DurationFilter>(
                chips = listOf(
                    DurationFilter.ALL to "All Durations",
                    DurationFilter.SHORT to "< 3 min",
                    DurationFilter.MEDIUM to "3-7 min",
                    DurationFilter.LONG to "> 7 min",
                ),
                currentValue = durationFilter,
                onValueUpdate = { viewModel.durationFilter.value = it },
            )
        }
        
        // Year filter chips (only show when filtering songs)
        if (searchFilter == LocalFilter.SONG || searchFilter == LocalFilter.ALL) {
            ChipsRow<YearFilter>(
                chips = listOf(
                    YearFilter.ALL to "All Years",
                    YearFilter.RECENT to "Recent (5y)",
                    YearFilter.DECADE_2020 to "2020s",
                    YearFilter.DECADE_2010 to "2010s",
                    YearFilter.DECADE_2000 to "2000s",
                    YearFilter.OLDER to "Older",
                ),
                currentValue = yearFilter,
                onValueUpdate = { viewModel.yearFilter.value = it },
            )
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.weight(1f),
        ) {
            result.map.forEach { (filter, items) ->
                if (result.filter == LocalFilter.ALL) {
                    item(key = filter) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ListItemHeight)
                                .clickable { viewModel.filter.value = filter }
                                .padding(start = 12.dp, end = 18.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    when (filter) {
                                        LocalFilter.SONG -> R.string.filter_songs
                                        LocalFilter.ALBUM -> R.string.filter_albums
                                        LocalFilter.ARTIST -> R.string.filter_artists
                                        LocalFilter.PLAYLIST -> R.string.filter_playlists
                                        LocalFilter.ALL -> error("")
                                    }
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )

                            Icon(
                                painter = painterResource(R.drawable.navigate_next),
                                contentDescription = null,
                            )
                        }
                    }
                }

                items(
                    items = items,
                    key = { it.id },
                    contentType = { CONTENT_TYPE_LIST },
                ) { item ->
                    when (item) {
                        is Song -> SongListItem(
                            song = item,
                            showInLibraryIcon = true,
                            isActive = item.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            SongMenu(
                                                originalSong = item,
                                                navController = navController,
                                                onDismiss = {
                                                    onDismiss()
                                                    menuState.dismiss()
                                                },
                                                isFromCache = isFromCache
                                            )
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        if (item.id == mediaMetadata?.id) {
                                            playerConnection.player.togglePlayPause()
                                        } else {
                                            val songs = result.map
                                                .getOrDefault(LocalFilter.SONG, emptyList())
                                                .filterIsInstance<Song>()
                                                .map { it.toMediaItem() }
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = context.getString(R.string.queue_searched_songs),
                                                    items = songs,
                                                    startIndex = songs.indexOfFirst { it.mediaId == item.id },
                                                )
                                            )
                                        }
                                    },
                                    onLongClick = {
                                        menuState.show {
                                            SongMenu(
                                                originalSong = item,
                                                navController = navController,
                                                onDismiss = {
                                                    onDismiss()
                                                    menuState.dismiss()
                                                },
                                                isFromCache = isFromCache
                                            )
                                        }
                                    }
                                )
                                .animateItem(),
                        )

                        is Album -> AlbumListItem(
                            album = item,
                            isActive = item.id == mediaMetadata?.album?.id,
                            isPlaying = isPlaying,
                            modifier = Modifier
                                .clickable {
                                    onDismiss()
                                    navController.navigate("album/${item.id}")
                                }
                                .animateItem(),
                        )

                        is Artist -> ArtistListItem(
                            artist = item,
                            modifier = Modifier
                                .clickable {
                                    onDismiss()
                                    navController.navigate("artist/${item.id}")
                                }
                                .animateItem(),
                        )

                        is Playlist -> PlaylistListItem(
                            playlist = item,
                            modifier = Modifier
                                .clickable {
                                    onDismiss()
                                    navController.navigate("local_playlist/${item.id}")
                                }
                                .animateItem(),
                        )
                    }
                }
            }

            if (result.query.isNotEmpty() && result.map.isEmpty()) {
                item(key = "no_result") {
                    EmptyPlaceholder(
                        icon = R.drawable.search,
                        text = stringResource(R.string.no_results_found),
                    )
                }
            }
        }
    }
}
