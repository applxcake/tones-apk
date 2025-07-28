@file:Suppress("DEPRECATION")

package com.tones.music.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes as LegacyAudioAttributes
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import android.util.Log
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.tones.innertube.YouTube
import com.tones.innertube.models.SongItem
import com.tones.innertube.models.WatchEndpoint
import com.tones.music.MainActivity
import com.tones.music.R
import com.tones.music.constants.AudioNormalizationKey
import com.tones.music.constants.AudioQuality
import com.tones.music.constants.AudioQualityKey
import com.tones.music.constants.AutoDownloadOnLikeKey
import com.tones.music.constants.AutoLoadMoreKey
import com.tones.music.constants.AutoSkipNextOnErrorKey
import com.tones.music.constants.PersistentQueueKey
import com.tones.music.constants.SimilarContent
import com.tones.music.constants.SkipSilenceKey
import com.tones.music.constants.StopMusicOnTaskClearKey
import com.tones.music.constants.SmoothTransitionEnabledKey
import com.tones.music.constants.SmoothTransitionDurationKey
import com.tones.music.constants.DiscordTokenKey
import com.tones.music.constants.EnableDiscordRPCKey
import com.tones.music.constants.HideExplicitKey
import com.tones.music.constants.HistoryDuration
import com.tones.music.constants.MediaSessionConstants.CommandToggleLike
import com.tones.music.constants.MediaSessionConstants.CommandToggleStartRadio
import com.tones.music.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.tones.music.constants.MediaSessionConstants.CommandToggleShuffle
import com.tones.music.constants.PauseListenHistoryKey
import com.tones.music.constants.PlayerVolumeKey
import com.tones.music.constants.RepeatModeKey
import com.tones.music.constants.ShowLyricsKey
import com.tones.music.db.MusicDatabase
import com.tones.music.db.entities.Event
import com.tones.music.db.entities.FormatEntity
import com.tones.music.db.entities.LyricsEntity
import com.tones.music.db.entities.RelatedSongMap
import com.tones.music.di.DownloadCache
import com.tones.music.di.PlayerCache
import com.tones.music.extensions.SilentHandler
import com.tones.music.extensions.collect
import com.tones.music.extensions.collectLatest
import com.tones.music.extensions.currentMetadata
import com.tones.music.extensions.findNextMediaItemById
import com.tones.music.extensions.mediaItems
import com.tones.music.extensions.metadata
import com.tones.music.extensions.toMediaItem
import com.tones.music.lyrics.LyricsHelper
import com.tones.music.models.PersistQueue
import com.tones.music.models.toMediaMetadata
import com.tones.music.playback.queues.EmptyQueue
import com.tones.music.playback.queues.ListQueue
import com.tones.music.playback.queues.Queue
import com.tones.music.playback.queues.YouTubeQueue
import com.tones.music.playback.queues.filterExplicit
import com.tones.music.utils.CoilBitmapLoader
import com.tones.music.utils.DiscordRPC
import com.tones.music.utils.SyncUtils
import com.tones.music.utils.YTPlayerUtils
import com.tones.music.utils.dataStore
import com.tones.music.utils.enumPreference
import com.tones.music.utils.get
import com.tones.music.utils.isInternetAvailable
import com.tones.music.utils.reportException
import com.tones.music.utils.NetworkConnectivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var hasAudioFocus = false

    private var scope = CoroutineScope(Dispatchers.Main) + Job()
    private val binder = MusicBinder()

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivity
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)

    private val audioQuality by enumPreference(
        this,
        AudioQualityKey,
        com.tones.music.constants.AudioQuality.AUTO
    )

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null

    val currentMediaMetadata = MutableStateFlow<com.tones.music.models.MediaMetadata?>(null)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    private val normalizeFactor = MutableStateFlow(1f)
    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    // Smooth transition variables
    private var smoothTransitionJob: Job? = null
    private var isSmoothTransitioning = false
    private var smoothTransitionStartTime = 0L
    private var smoothTransitionDuration = 3000L // Default 3 seconds
    private var smoothTransitionMonitorJob: Job? = null
    private var nextSongPlayer: ExoPlayer? = null // For overlapping playback

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    private var isAudioEffectSessionOpened = false

    private var discordRpc: DiscordRPC? = null

    val automixItems = MutableStateFlow<List<MediaItem>>(emptyList())

    private var consecutivePlaybackErr = 0

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            )
                .apply {
                    setSmallIcon(R.drawable.small_icon)
                },
        )
        player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
                .setRenderersFactory(createRenderersFactory())
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false,
                ).setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .build()
                .apply {
                    addListener(this@MusicService)
                    sleepTimer = SleepTimer(scope, this)
                    addListener(sleepTimer)
                    addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
                    addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            super.onMediaItemTransition(mediaItem, reason)
                            
                            Log.d("MusicService", "Media item transition: reason=$reason, isSmoothTransitioning=$isSmoothTransitioning")
                            
                            // Only handle smooth transition for natural transitions when not already transitioning
                            if ((reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) && 
                                !isSmoothTransitioning) {
                                Log.d("MusicService", "Calling handleSmoothTransition from media transition")
                                handleSmoothTransition()
                            }
                            
                            mediaItem?.mediaId?.let { mediaId ->
                                scope.launch {
                                    val song = database.song(mediaId).first()
                                    if (song != null) {
                                        discordRpc?.updateSong(song)
                                    }
                                }
                            }
                        }
                    })
                }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupAudioFocusRequest()

        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!
        connectivityObserver = NetworkConnectivity(this)

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    waitingForNetworkConnection.value = false
                    player.prepare()
                    player.play()
                }
            }
        }

        combine(playerVolume, normalizeFactor) { playerVolume, normalizeFactor ->
            playerVolume * normalizeFactor
        }.collectLatest(scope) {
            player.volume = it
        }

        playerVolume.debounce(1000).collect(scope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(1000).collect(scope) { song ->
            updateNotification()
            if (song != null) {
                discordRpc?.updateSong(song)
            } else {
                discordRpc?.closeRPC()
            }
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(scope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database.lyrics(mediaMetadata.id)
                    .first() == null
            ) {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = lyrics,
                        ),
                    )
                }
            }
        }

        // Collect smooth transition settings
        combine(
            dataStore.data.map { it[SmoothTransitionEnabledKey] ?: false }.distinctUntilChanged(),
            dataStore.data.map { it[SmoothTransitionDurationKey] ?: 3 }.distinctUntilChanged()
        ) { enabled, duration ->
            if (enabled) {
                smoothTransitionDuration = (duration * 1000).toLong() // Convert seconds to milliseconds
            }
            // Restart smooth transition monitoring when settings change
            startSmoothTransitionMonitoring()
        }.collect(scope) { }

        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                player.skipSilenceEnabled = it
            }

        combine(
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
        ) { format, normalizeAudio ->
            format to normalizeAudio
        }.collectLatest(scope) { (format, normalizeAudio) ->
            normalizeFactor.value =
                if (normalizeAudio && format?.loudnessDb != null) {
                    min(10f.pow(-format.loudnessDb.toFloat() / 20), 1f)
                } else {
                    1f
                }
        }

        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { (key, enabled) ->
                if (discordRpc?.isRpcRunning() == true) {
                    discordRpc?.closeRPC()
                }
                discordRpc = null
                if (key != null && enabled) {
                    discordRpc = DiscordRPC(this, key)
                    currentSong.value?.let {
                        discordRpc?.updateSong(it)
                    }
                }
            }

        if (dataStore.get(PersistentQueueKey, true)) {
            runCatching {
                filesDir.resolve(PERSISTENT_QUEUE_FILE).inputStream().use { fis ->
                    ObjectInputStream(fis).use { oos ->
                        oos.readObject() as PersistQueue
                    }
                }
            }.onSuccess { queue ->
                playQueue(
                    queue =
                    ListQueue(
                        title = queue.title,
                        items = queue.items.map { it.toMediaItem() },
                        startIndex = queue.mediaItemIndex,
                        position = queue.position,
                    ),
                    playWhenReady = false,
                )
            }
            runCatching {
                filesDir.resolve(PERSISTENT_AUTOMIX_FILE).inputStream().use { fis ->
                    ObjectInputStream(fis).use { oos ->
                        oos.readObject() as PersistQueue
                    }
                }
            }.onSuccess { queue ->
                automixItems.value = queue.items.map { it.toMediaItem() }
            }
        }

        // Save queue periodically to prevent queue loss from crash or force kill
        scope.launch {
            while (isActive) {
                delay(30.seconds)
                if (dataStore.get(PersistentQueueKey, true)) {
                    saveQueueToDisk()
                }
            }
        }
    }

    private fun setupAudioFocusRequest() {
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                handleAudioFocusChange(focusChange)
            }
            .setAcceptsDelayedFocusGain(true)
            .build()
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true

                if (wasPlayingBeforeAudioFocusLoss) {
                    player.play()
                    wasPlayingBeforeAudioFocusLoss = false
                }

                player.volume = (playerVolume.value * normalizeFactor.value)

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                wasPlayingBeforeAudioFocusLoss = false

                if (player.isPlaying) {
                    player.pause()
                }

                abandonAudioFocus()

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                wasPlayingBeforeAudioFocusLoss = player.isPlaying

                if (player.isPlaying) {
                    player.pause()
                }

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {

                hasAudioFocus = false

                wasPlayingBeforeAudioFocusLoss = player.isPlaying

                if (player.isPlaying) {
                    player.volume = (playerVolume.value * normalizeFactor.value * 0.2f) // خفض إلى 20%
                }

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {

                hasAudioFocus = true

                if (wasPlayingBeforeAudioFocusLoss) {
                    player.play()
                    wasPlayingBeforeAudioFocusLoss = false
                }

                player.volume = (playerVolume.value * normalizeFactor.value)
        
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                hasAudioFocus = true

                player.volume = (playerVolume.value * normalizeFactor.value)

                lastAudioFocusState = focusChange
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
    
        audioFocusRequest?.let { request ->
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return hasAudioFocus
        }
        return false
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
                hasAudioFocus = false
            }
        }
    }

    fun hasAudioFocusForPlayback(): Boolean {
        return hasAudioFocus
    }

    private fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
    }

    private fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            return
        }

        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            if (currentSong.value?.song?.liked ==
                                true
                            ) {
                                R.string.action_remove_like
                            } else {
                                R.string.action_like
                            },
                        ),
                    )
                    .setIconResId(if (currentSong.value?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border)
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            },
                        ),
                    ).setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        },
                    ).setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(getString(R.string.start_radio))
                    .setIconResId(R.drawable.radio)
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build(),
            ),
        )
    }

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(mediaId)
                .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            if (song == null) insert(mediaMetadata.copy(duration = duration))
            else if (song.song.duration == -1) update(song.song.copy(duration = duration))
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id
                        )
                    }
                    .forEach(::insert)
            }
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        if (!scope.isActive) scope = CoroutineScope(Dispatchers.Main) + Job()
        currentQueue = queue
        queueTitle = null
        player.shuffleModeEnabled = false
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }
        scope.launch(SilentHandler) {
            val initialStatus =
                withContext(Dispatchers.IO) {
                    queue.getInitialStatus().filterExplicit(dataStore.get(HideExplicitKey, false))
                }
            if (queue.preloadItem != null && player.playbackState == STATE_IDLE) return@launch
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            if (queue.preloadItem != null) {
                player.addMediaItems(
                    0,
                    initialStatus.items.subList(0, initialStatus.mediaItemIndex)
                )
                player.addMediaItems(
                    initialStatus.items.subList(
                        initialStatus.mediaItemIndex + 1,
                        initialStatus.items.size
                    )
                )
            } else {
                player.setMediaItems(
                    initialStatus.items,
                    if (initialStatus.mediaItemIndex >
                        0
                    ) {
                        initialStatus.mediaItemIndex
                    } else {
                        0
                    },
                    initialStatus.position,
                )
                player.prepare()
                player.playWhenReady = playWhenReady
            }
        }
    }

    fun startRadioSeamlessly() {
        val currentMediaMetadata = player.currentMetadata ?: return
        if (player.currentMediaItemIndex > 0) player.removeMediaItems(
            0,
            player.currentMediaItemIndex
        )
        if (player.currentMediaItemIndex <
            player.mediaItemCount - 1
        ) {
            player.removeMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount)
        }
        scope.launch(SilentHandler) {
            val radioQueue =
                YouTubeQueue(endpoint = WatchEndpoint(videoId = currentMediaMetadata.id))
            val initialStatus = radioQueue.getInitialStatus()
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            player.addMediaItems(initialStatus.items.drop(1))
            currentQueue = radioQueue
        }
    }

    fun getAutomixAlbum(albumId: String) {
        scope.launch(SilentHandler) {
            YouTube
                .album(albumId)
                .onSuccess {
                    getAutomix(it.album.playlistId)
                }
        }
    }

    fun getAutomix(playlistId: String) {
        if (dataStore[SimilarContent] == true) {
            scope.launch(SilentHandler) {
                YouTube
                    .next(WatchEndpoint(playlistId = playlistId))
                    .onSuccess {
                        YouTube
                            .next(WatchEndpoint(playlistId = it.endpoint.playlistId))
                            .onSuccess {
                                automixItems.value =
                                    it.items.map { song ->
                                        song.toMediaItem()
                                    }
                            }
                    }
            }
        }
    }

    fun addToQueueAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        addToQueue(listOf(item))
    }

    fun playNextAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        playNext(listOf(item))
    }

    fun clearAutomix() {
        filesDir.resolve(PERSISTENT_QUEUE_FILE).delete()
        automixItems.value = emptyList()
    }

    fun playNext(items: List<MediaItem>) {
        player.addMediaItems(
            if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1,
            items
        )
        player.prepare()
    }

    fun addToQueue(items: List<MediaItem>) {
        player.addMediaItems(items)
        player.prepare()
    }

    private fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
         database.query {
             currentSong.value?.let {
                 val song = it.song.toggleLike()
                 update(song)
                 syncUtils.likeSong(song)

                 // Check if auto-download on like is enabled and the song is now liked
                 if (dataStore.get(AutoDownloadOnLikeKey, false) && song.liked) {
                     // Trigger download for the liked song
                     val downloadRequest = androidx.media3.exoplayer.offline.DownloadRequest
                         .Builder(song.id, song.id.toUri())
                         .setCustomCacheKey(song.id)
                         .setData(song.title.toByteArray())
                         .build()
                     androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                         this@MusicService,
                         ExoDownloadService::class.java,
                         downloadRequest,
                         false
                     )
                 }
             }
         }
     }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        // Cancel any ongoing smooth transition when manually skipping
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            smoothTransitionJob?.cancel()
            smoothTransitionMonitorJob?.cancel()
            nextSongPlayer?.release()
            nextSongPlayer = null
            // Restore volume immediately for manual skips
            player.volume = playerVolume.value * normalizeFactor.value
            return
        }
        
        // Start monitoring for smooth transition on new media item
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            startSmoothTransitionMonitoring()
        }
        
        // Auto load more songs
        if (dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            currentQueue.hasNextPage()
        ) {
            scope.launch(SilentHandler) {
                val mediaItems =
                    currentQueue.nextPage().filterExplicit(dataStore.get(HideExplicitKey, false))
                if (player.playbackState != STATE_IDLE) {
                    player.addMediaItems(mediaItems.drop(1))
                }
            }
        }
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        if (playbackState == STATE_IDLE) {
            currentQueue = EmptyQueue
            player.shuffleModeEnabled = false
            queueTitle = null
        }
        
        // Start smooth transition monitoring when playback is ready
        if (playbackState == Player.STATE_READY && player.playWhenReady) {
            startSmoothTransitionMonitoring()
        }
        
        // Cancel smooth transition if user is seeking or buffering
        if (playbackState == Player.STATE_BUFFERING) {
            smoothTransitionJob?.cancel()
            smoothTransitionMonitorJob?.cancel()
            // Restore volume during buffering
            player.volume = playerVolume.value * normalizeFactor.value
        }
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED
            )
        ) {
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                val focusGranted = requestAudioFocus()
                if (focusGranted) {
                    openAudioEffectSession()
                }
            } else {
                closeAudioEffectSession()
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
            
            // Cancel smooth transition if user seeks within the song
            if (events.contains(EVENT_POSITION_DISCONTINUITY)) {
                smoothTransitionJob?.cancel()
                smoothTransitionMonitorJob?.cancel()
                // Restore volume immediately
                player.volume = playerVolume.value * normalizeFactor.value
                // Restart smooth transition monitoring after a short delay
                scope.launch {
                    delay(500)
                    startSmoothTransitionMonitoring()
                }
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        if (shuffleModeEnabled) {
            // Always put current playing item at first
            val shuffledIndices = IntArray(player.mediaItemCount) { it }
            shuffledIndices.shuffle()
            shuffledIndices[shuffledIndices.indexOf(player.currentMediaItemIndex)] =
                shuffledIndices[0]
            shuffledIndices[0] = player.currentMediaItemIndex
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        val isConnectionError = (error.cause?.cause is PlaybackException) &&
                (error.cause?.cause as PlaybackException).errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

        if (!isNetworkConnected.value || isConnectionError) {
            waitOnNetworkError()
            return
        }

        if (dataStore.get(AutoSkipNextOnErrorKey, false)) {
            skipOnError()
        } else {
            stopOnError()
        }
    }

    private fun createCacheDataSource(): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                OkHttpClient
                                    .Builder()
                                    .proxy(YouTube.proxy)
                                    .build(),
                            ),
                        ),
                    ),
            ).setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    private fun createDataSourceFactory(): DataSource.Factory {
        val songUrlCache = HashMap<String, Pair<String, Long>>()
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")

            if (downloadCache.isCached(
                    mediaId,
                    dataSpec.position,
                    if (dataSpec.length >= 0) dataSpec.length else 1
                ) ||
                playerCache.isCached(mediaId, dataSpec.position, CHUNK_LENGTH)
            ) {
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                return@Factory dataSpec
            }

            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                return@Factory dataSpec.withUri(it.first.toUri())
            }

            val playbackData = runBlocking(Dispatchers.IO) {
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                )
            }.getOrNull()

            if (playbackData == null) {
                throw PlaybackException(
                    getString(R.string.error_unknown),
                    null,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR
                )
            } else {
                val format = playbackData.format

                database.query {
                    upsert(
                        FormatEntity(
                            id = mediaId,
                            itag = format.itag,
                            mimeType = format.mimeType.split(";")[0],
                            codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                            bitrate = format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLength = format.contentLength!!,
                            loudnessDb = playbackData.audioConfig?.loudnessDb,
                            playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                        )
                    )
                }
                scope.launch(Dispatchers.IO) { recoverSong(mediaId, playbackData) }

                val streamUrl = playbackData.streamUrl

                songUrlCache[mediaId] =
                    streamUrl to System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
                return@Factory dataSpec.withUri(streamUrl.toUri()).subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
            }
        }
    }

    private fun createMediaSourceFactory() =
        DefaultMediaSourceFactory(
            createDataSourceFactory(),
            ExtractorsFactory {
                arrayOf(MatroskaExtractor(), FragmentedMp4Extractor())
            },
        )

    private fun createRenderersFactory() =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink
                .Builder(this@MusicService)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray(),
                        SilenceSkippingAudioProcessor(2_000_000, 20_000, 256),
                        SonicAudioProcessor(),
                    ),
                ).build()
        }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem

        if (playbackStats.totalPlayTimeMs >= (
                dataStore[HistoryDuration]?.times(1000f)
                    ?: 30000f
            ) &&
            !dataStore.get(PauseListenHistoryKey, false)
        ) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs,
                        ),
                    )
                } catch (_: SQLException) {
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val playbackUrl = database.format(mediaItem.mediaId).first()?.playbackUrl
                ?: YTPlayerUtils.playerResponseForMetadata(mediaItem.mediaId, null)
                    .getOrNull()?.playbackTracking?.videostatsPlaybackUrl?.baseUrl
            playbackUrl?.let {
                YouTube.registerPlayback(null, playbackUrl)
                    .onFailure {
                        reportException(it)
                    }
                }
            }
        }
    }

    private fun saveQueueToDisk() {
        if (player.playbackState == STATE_IDLE) {
            filesDir.resolve(PERSISTENT_AUTOMIX_FILE).delete()
            filesDir.resolve(PERSISTENT_QUEUE_FILE).delete()
            return
        }
        val persistQueue =
            PersistQueue(
                title = queueTitle,
                items = player.mediaItems.mapNotNull { it.metadata },
                mediaItemIndex = player.currentMediaItemIndex,
                position = player.currentPosition,
            )
        val persistAutomix =
            PersistQueue(
                title = "automix",
                items = automixItems.value.mapNotNull { it.metadata },
                mediaItemIndex = 0,
                position = 0,
            )
        runCatching {
            filesDir.resolve(PERSISTENT_QUEUE_FILE).outputStream().use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(persistQueue)
                }
            }
        }.onFailure {
            reportException(it)
        }
        runCatching {
            filesDir.resolve(PERSISTENT_AUTOMIX_FILE).outputStream().use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(persistAutomix)
                }
            }
        }.onFailure {
            reportException(it)
        }
    }

    override fun onDestroy() {
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
        if (discordRpc?.isRpcRunning() == true) {
            discordRpc?.closeRPC()
        }
        discordRpc = null
        smoothTransitionJob?.cancel()
        smoothTransitionMonitorJob?.cancel()
        nextSongPlayer?.release()
        nextSongPlayer = null
        abandonAudioFocus()
        mediaSession.release()
        player.removeListener(this)
        player.removeListener(sleepTimer)
        player.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    /**
     * Starts monitoring the current song for smooth transition timing
     */
    private fun startSmoothTransitionMonitoring() {
        smoothTransitionMonitorJob?.cancel()
        
        val smoothTransitionEnabled = dataStore.get(SmoothTransitionEnabledKey, false)
        if (!smoothTransitionEnabled || smoothTransitionDuration <= 0) {
            return
        }
        
        smoothTransitionMonitorJob = scope.launch {
            try {
                while (isActive) {
                    delay(50) // Check more frequently for precise timing
                    
                    val currentPosition = player.currentPosition
                    val duration = player.duration
                    
                    // Start smooth transition when we're exactly at the smooth transition duration from the end
                    // This creates true overlapping smooth transition - the next song will start playing
                    // while the current song is still playing, creating the overlap
                    if (duration > 0 && currentPosition > 0 && 
                        (duration - currentPosition) <= smoothTransitionDuration && !isSmoothTransitioning) {
                        Log.d("MusicService", "Triggering smooth transition: ${duration - currentPosition}ms remaining")
                        // Start overlapping smooth transition
                        startSpotifyStyleSmoothTransition()
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicService", "Error in smooth transition monitoring", e)
            }
        }
    }

    /**
     * Implements Spotify-style smooth transition with overlapping audio
     */
    private fun startSpotifyStyleSmoothTransition() {
        smoothTransitionJob?.cancel()
        
        smoothTransitionJob = scope.launch {
            try {
                isSmoothTransitioning = true
                smoothTransitionStartTime = System.currentTimeMillis()
                Log.d("MusicService", "Starting Spotify-style smooth transition, duration: ${smoothTransitionDuration}ms")
                
                // Get current volume and ensure we have a next song
                val startVolume = playerVolume.value * normalizeFactor.value
                val currentIndex = player.currentMediaItemIndex
                val nextIndex = currentIndex + 1
                
                if (nextIndex >= player.mediaItemCount) {
                    // No next song, just fade out current
                    Log.d("MusicService", "No next song, performing fade-out only")
                    performFadeOutOnly(startVolume)
                    return@launch
                }
                
                // Use proper overlapping smooth transition
                Log.d("MusicService", "Performing true overlapping smooth transition")
                performTrueOverlappingSmoothTransition(startVolume)
                
            } catch (e: Exception) {
                Log.e("MusicService", "Error during smooth transition", e)
                // If smooth transition fails, restore volume
                player.volume = playerVolume.value * normalizeFactor.value
            } finally {
                isSmoothTransitioning = false
            }
        }
    }
    
    /**
     * Creates a second ExoPlayer instance for the next song
     */
    private fun createNextSongPlayer(): ExoPlayer? {
        return try {
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
                .setRenderersFactory(createRenderersFactory())
                .setHandleAudioBecomingNoisy(false) // Don't handle audio becoming noisy for second player
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false,
                ).build()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Performs true Spotify-style overlapping smooth transition
     */
    private suspend fun performTrueOverlappingSmoothTransition(targetVolume: Float) {
        try {
            val currentIndex = player.currentMediaItemIndex
            val nextIndex = currentIndex + 1
            
            if (nextIndex >= player.mediaItemCount) {
                Log.d("MusicService", "No next song available")
                return
            }
            
            // Get the next media item
            val nextMediaItem = player.mediaItems.getOrNull(nextIndex)
            if (nextMediaItem == null) {
                Log.d("MusicService", "Next media item not found")
                return
            }
            
            Log.d("MusicService", "Setting up dual-player overlapping smooth transition")
            
            // Create a second player for the next song
            nextSongPlayer = createNextSongPlayer()
            if (nextSongPlayer == null) {
                Log.d("MusicService", "Failed to create next player, falling back to simple smooth transition")
                performSimpleSmoothTransition(targetVolume)
                return
            }
            
            val nextPlayer = nextSongPlayer!!
            
            // Set up the next player with the next song
            nextPlayer.setMediaItem(nextMediaItem)
            nextPlayer.prepare()
            
            // Start the next song at volume 0
            nextPlayer.volume = 0f
            nextPlayer.play()
            
            // Wait a moment for the next player to start
            delay(100)
            
            Log.d("MusicService", "Both players ready, starting volume transition")
            
            // Perform the overlapping smooth transition with smooth volume curves
            val fadeSteps = 100 // More steps for smoother transition
            val fadeStepDuration = smoothTransitionDuration / fadeSteps
            
            for (i in 0 until fadeSteps) {
                val progress = i.toFloat() / fadeSteps
                
                // Use smooth curves for better audio experience
                // Current song: fade out with ease-out curve
                val currentVolume = targetVolume * (1f - (progress * progress))
                player.volume = currentVolume.coerceAtLeast(0f)
                
                // Next song: fade in with ease-in curve, delayed start
                val fadeInDelay = 0.3f // Start fading in after 30% of smooth transition
                val fadeInProgress = if (progress > fadeInDelay) {
                    ((progress - fadeInDelay) / (1f - fadeInDelay)).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val nextVolume = targetVolume * (fadeInProgress * fadeInProgress)
                nextPlayer.volume = nextVolume.coerceAtMost(targetVolume)
                
                delay(fadeStepDuration)
            }
            
            // Ensure final volumes are correct
            player.volume = 0f
            nextPlayer.volume = targetVolume
            
            // Get the current position of the next player before stopping it
            val nextPlayerPosition = nextPlayer.currentPosition
            
            Log.d("MusicService", "Smooth transition complete, transitioning to main player at position: $nextPlayerPosition")
            
            // Stop the next player since we're about to transition
            nextPlayer.stop()
            nextPlayer.release()
            nextSongPlayer = null
            
            // Now advance the main player to the next song
            player.seekToNext()
            
            // Wait for the transition
            delay(100)
            
            // Seek to the same position as the next player was at
            player.seekTo(nextPlayerPosition)
            
            // Restore normal volume
            player.volume = playerVolume.value * normalizeFactor.value
            
            Log.d("MusicService", "Dual-player overlapping smooth transition completed successfully")
            
        } catch (e: Exception) {
            Log.e("MusicService", "Error during dual-player smooth transition", e)
            // Clean up on error
            nextSongPlayer?.let { player ->
                try {
                    player.stop()
                    player.release()
                } catch (e: Exception) {
                    Log.e("MusicService", "Error cleaning up next player", e)
                }
            }
            nextSongPlayer = null
            
            // Fall back to simple smooth transition
            performSimpleSmoothTransition(targetVolume)
        }
    }
    
    /**
     * Performs fade-out only when no next song is available
     */
    private suspend fun performFadeOutOnly(startVolume: Float) {
        val fadeSteps = 50
        val fadeStepDuration = smoothTransitionDuration / fadeSteps
        
        for (i in 0 until fadeSteps) {
            val progress = i.toFloat() / fadeSteps
            val currentVolume = startVolume * (1f - progress)
            player.volume = currentVolume.coerceAtLeast(0f)
            delay(fadeStepDuration)
        }
        
        player.volume = 0f
    }

    /**
     * Fallback simple smooth transition implementation
     */
    private suspend fun performSimpleSmoothTransition(targetVolume: Float) {
        // First, fade out the current song
        val fadeOutSteps = 30
        val fadeOutStepDuration = smoothTransitionDuration / 2 / fadeOutSteps
        
        Log.d("MusicService", "Starting simple fade-out, steps: $fadeOutSteps, stepDuration: ${fadeOutStepDuration}ms")
        
        for (i in 0 until fadeOutSteps) {
            val progress = i.toFloat() / fadeOutSteps
            val currentVolume = targetVolume * (1f - progress)
            player.volume = currentVolume.coerceAtLeast(0f)
            Log.d("MusicService", "Simple fade-out step $i: volume = $currentVolume")
            delay(fadeOutStepDuration)
        }
        
        // Now advance to the next song
        player.seekToNext()
        
        // Wait a moment for the new song to start
        delay(200)
        
        // Fade in the new song with a more gradual curve
        val fadeInSteps = 30
        val fadeInStepDuration = smoothTransitionDuration / 2 / fadeInSteps
        
        Log.d("MusicService", "Starting simple fade-in, steps: $fadeInSteps, stepDuration: ${fadeInStepDuration}ms")
        
        // Start with a very low volume to ensure fade-in is noticeable
        player.volume = 0f
        
        for (i in 0 until fadeInSteps) {
            val progress = i.toFloat() / fadeInSteps
            // Use a quadratic curve for smoother fade-in
            val fadeInProgress = progress * progress
            val newVolume = targetVolume * fadeInProgress
            player.volume = newVolume.coerceAtMost(targetVolume)
            Log.d("MusicService", "Simple fade-in step $i: volume = $newVolume")
            delay(fadeInStepDuration)
        }
        
        // Ensure final volume is correct
        player.volume = playerVolume.value * normalizeFactor.value
    }

    /**
     * Handles the transition to the new song with fade-in
     * This is a fallback for when the main smooth transition logic doesn't handle the transition
     */
    private fun handleSmoothTransition() {
        // This handles the fade-in of the new song after transition
        smoothTransitionJob?.cancel()
        
        val smoothTransitionEnabled = dataStore.get(SmoothTransitionEnabledKey, false)
        if (!smoothTransitionEnabled || smoothTransitionDuration <= 0) {
            Log.d("MusicService", "Smooth transition disabled or duration <= 0")
            return
        }
        
        // Only proceed if we're not already in the middle of a smooth transition
        if (isSmoothTransitioning) {
            Log.d("MusicService", "Already smooth transitioning, skipping handleSmoothTransition")
            return
        }
        
        Log.d("MusicService", "Handling smooth transition")
        
        // Fade in the new song
        smoothTransitionJob = scope.launch {
            try {
                val targetVolume = playerVolume.value * normalizeFactor.value
                
                // Start with very low volume to ensure fade-in is noticeable
                player.volume = 0f
                
                // Fade in over the smooth transition duration
                val fadeInSteps = 30
                val fadeInStepDuration = smoothTransitionDuration / fadeInSteps
                
                for (i in 0 until fadeInSteps) {
                    val progress = i.toFloat() / fadeInSteps
                    // Use a quadratic curve for smoother fade-in
                    val fadeInProgress = progress * progress
                    val newVolume = targetVolume * fadeInProgress
                    player.volume = newVolume.coerceAtMost(targetVolume)
                    delay(fadeInStepDuration)
                }
                
                // Restore original volume
                player.volume = targetVolume
                
            } catch (e: Exception) {
                // If smooth transition fails, restore volume
                player.volume = playerVolume.value * normalizeFactor.value
            }
        }
    }

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
        const val MAX_CONSECUTIVE_ERR = 5
    }
}

