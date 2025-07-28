package com.tones.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tones.music.db.MusicDatabase
import com.tones.music.db.entities.Album
import com.tones.music.db.entities.Artist
import com.tones.music.db.entities.LocalItem
import com.tones.music.db.entities.Playlist
import com.tones.music.db.entities.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalSearchViewModel
@Inject
constructor(
    database: MusicDatabase,
) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LocalFilter.ALL)
    val durationFilter = MutableStateFlow(DurationFilter.ALL)
    val yearFilter = MutableStateFlow(YearFilter.ALL)

    val result =
        combine(query, filter, durationFilter, yearFilter) { query, filter, durationFilter, yearFilter ->
            SearchParams(query, filter, durationFilter, yearFilter)
        }.flatMapLatest { params ->
            if (params.query.isEmpty()) {
                flowOf(LocalSearchResult("", params.filter, emptyMap()))
            } else {
                when (params.filter) {
                    LocalFilter.ALL ->
                        combine(
                            database.searchSongs(params.query, PREVIEW_SIZE),
                            database.searchAlbums(params.query, PREVIEW_SIZE),
                            database.searchArtists(params.query, PREVIEW_SIZE),
                            database.searchPlaylists(params.query, PREVIEW_SIZE),
                        ) { songs, albums, artists, playlists ->
                            songs + albums + artists + playlists
                        }

                    LocalFilter.SONG -> database.searchSongs(params.query)
                    LocalFilter.ALBUM -> database.searchAlbums(params.query)
                    LocalFilter.ARTIST -> database.searchArtists(params.query)
                    LocalFilter.PLAYLIST -> database.searchPlaylists(params.query)
                }.map { list ->
                    // Apply duration and year filters
                    val filteredList = list.filter { item ->
                        when (item) {
                            is Song -> {
                                val durationMatch = when (params.durationFilter) {
                                    DurationFilter.ALL -> true
                                    DurationFilter.SHORT -> item.song.duration < 180 // < 3 minutes (in seconds)
                                    DurationFilter.MEDIUM -> item.song.duration in 180..420 // 3-7 minutes (in seconds)
                                    DurationFilter.LONG -> item.song.duration > 420 // > 7 minutes (in seconds)
                                }
                                
                                val yearMatch = when (params.yearFilter) {
                                    YearFilter.ALL -> true
                                    YearFilter.RECENT -> item.song.year != null && item.song.year >= 2019
                                    YearFilter.DECADE_2020 -> item.song.year != null && item.song.year in 2020..2029
                                    YearFilter.DECADE_2010 -> item.song.year != null && item.song.year in 2010..2019
                                    YearFilter.DECADE_2000 -> item.song.year != null && item.song.year in 2000..2009
                                    YearFilter.OLDER -> item.song.year != null && item.song.year < 2000
                                }
                                
                                durationMatch && yearMatch
                            }
                            else -> true // Don't filter non-song items
                        }
                    }
                    
                    LocalSearchResult(
                        query = params.query,
                        filter = params.filter,
                        map = filteredList.groupBy {
                            when (it) {
                                is Song -> LocalFilter.SONG
                                is Album -> LocalFilter.ALBUM
                                is Artist -> LocalFilter.ARTIST
                                is Playlist -> LocalFilter.PLAYLIST
                            }
                        },
                    )
                }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            LocalSearchResult("", filter.value, emptyMap())
        )

    companion object {
        const val PREVIEW_SIZE = 3
    }
}

enum class LocalFilter {
    ALL,
    SONG,
    ALBUM,
    ARTIST,
    PLAYLIST,
}

enum class DurationFilter {
    ALL,
    SHORT, // < 3 minutes
    MEDIUM, // 3-7 minutes
    LONG, // > 7 minutes
}

enum class YearFilter {
    ALL,
    RECENT, // Last 5 years
    DECADE_2020, // 2020-2029
    DECADE_2010, // 2010-2019
    DECADE_2000, // 2000-2009
    OLDER, // Before 2000
}

data class SearchParams(
    val query: String,
    val filter: LocalFilter,
    val durationFilter: DurationFilter,
    val yearFilter: YearFilter,
)

data class LocalSearchResult(
    val query: String,
    val filter: LocalFilter,
    val map: Map<LocalFilter, List<LocalItem>>,
)
