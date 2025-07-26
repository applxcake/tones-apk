package com.metrolist.music.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel @Inject constructor(
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!
    var artistPage by mutableStateOf<ArtistPage?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    val libraryArtist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val librarySongs = database.artistSongsPreview(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = database.artistAlbumsPreview(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        fetchArtistsFromYTM()
    }

    fun fetchArtistsFromYTM() {
        // Validate artist ID before making the API call
        if (artistId.isBlank() || artistId == "null") {
            errorMessage = "Invalid artist ID. Cannot load artist profile."
            return
        }
        
        viewModelScope.launch {
            YouTube.artist(artistId)
                .onSuccess { page ->
                    val filteredSections = page.sections.filterNot { section ->
                        section.title.equals("From your library", ignoreCase = true)
                    }
                    artistPage = page.copy(sections = filteredSections)
                    errorMessage = null
                }.onFailure { exception ->
                    val msg = exception.localizedMessage ?: "Failed to load artist profile."
                    
                    // Handle different types of errors
                    when {
                        msg.contains("NOT_FOUND", ignoreCase = true) || 
                        msg.contains("not found", ignoreCase = true) ||
                        msg.contains("400", ignoreCase = true) ||
                        msg.contains("INVALID_ARGUMENT", ignoreCase = true) -> {
                            errorMessage = "This artist profile is not available online. It may be a local artist or the profile has been removed."
                        }
                        msg.contains("403", ignoreCase = true) || 
                        msg.contains("FORBIDDEN", ignoreCase = true) -> {
                            errorMessage = "Access to this artist profile is restricted."
                        }
                        msg.contains("500", ignoreCase = true) || 
                        msg.contains("INTERNAL_ERROR", ignoreCase = true) -> {
                            errorMessage = "YouTube Music is experiencing issues. Please try again later."
                        }
                        else -> {
                            errorMessage = "Unable to load artist profile: $msg"
                        }
                    }
                    reportException(exception)
                }
        }
    }
    fun clearError() { errorMessage = null }
}
