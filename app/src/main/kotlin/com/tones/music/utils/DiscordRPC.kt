package com.tones.music.utils

import android.content.Context
import com.tones.music.db.entities.Song
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.util.Base64

class DiscordRPC(
    val context: Context,
    token: String,
) : KizzyRPC(token) {
    companion object {
        private const val APPLICATION_ID = "1271273225120125040"
    }

    private fun proxiedImageUrl(originalUrl: String?): String? {
        if (originalUrl.isNullOrBlank()) return null
        
        // Check if it's already a valid URL
        if (originalUrl.startsWith("http://") || originalUrl.startsWith("https://")) {
            val urlWithoutProtocol = originalUrl.removePrefix("https://").removePrefix("http://")
            return "https://images.weserv.nl/?url=$urlWithoutProtocol&w=512&h=512&fit=cover"
        }
        
        return null
    }

    suspend fun updateSong(song: Song) = runCatching {
        val proxiedUrl = proxiedImageUrl(song.song.thumbnailUrl)
        
        setActivity(
            name = "Tones",
            details = song.song.title,
            state = song.song.artistName ?: song.artists.joinToString { it.name },
            largeImage = proxiedUrl?.let { RpcImage.ExternalImage(it) } ?: RpcImage.DiscordImage("logo"),
            smallImage = null,
            largeText = song.album?.title,
            buttons = listOf(
                "Listen on YouTube Music" to "https://music.youtube.com/watch?v=${song.song.id}",
                "Visit Tones" to "https://github.com/mostafaalagamy/Metrolist"
            ),
            type = Type.LISTENING,
            since = System.currentTimeMillis(),
            applicationId = APPLICATION_ID
        )
    }
}
