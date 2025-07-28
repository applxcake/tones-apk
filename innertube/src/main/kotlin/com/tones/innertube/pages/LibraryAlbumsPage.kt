package com.tones.innertube.pages

import com.tones.innertube.models.Album
import com.tones.innertube.models.AlbumItem
import com.tones.innertube.models.Artist
import com.tones.innertube.models.ArtistItem
import com.tones.innertube.models.MusicResponsiveListItemRenderer
import com.tones.innertube.models.MusicTwoRowItemRenderer
import com.tones.innertube.models.PlaylistItem
import com.tones.innertube.models.SongItem
import com.tones.innertube.models.YTItem
import com.tones.innertube.models.oddElements
import com.tones.innertube.utils.parseTime

data class LibraryAlbumsPage(
    val albums: List<AlbumItem>,
    val continuation: String?,
) {
    companion object {
        fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): AlbumItem? {
            return AlbumItem(
                        browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint?.playlistId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = null,
                        year = renderer.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit = renderer.subtitleBadges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null
                    )
        }
    }
}
