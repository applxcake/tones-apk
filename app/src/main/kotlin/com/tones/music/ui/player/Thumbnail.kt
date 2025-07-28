package com.tones.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.tones.music.LocalPlayerConnection
import com.tones.music.R
import com.tones.music.constants.PlayerHorizontalPadding
import com.tones.music.constants.ShowLyricsKey
import com.tones.music.constants.SwipeThumbnailKey
import com.tones.music.constants.ThumbnailCornerRadius
import com.tones.music.ui.component.Lyrics
import com.tones.music.utils.rememberPreference
import com.tones.music.utils.dataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.navigation.NavController

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Thumbnail(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    navController: NavController, // Add this parameter
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val currentView = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    // States
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val error by playerConnection.error.collectAsState()
    val showLyrics by rememberPreference(ShowLyricsKey, false)
    val swipeThumbnail by rememberPreference(SwipeThumbnailKey, true)
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    
    // Animation states for album art transitions
    var previousMediaId by remember { mutableStateOf<String?>(null) }
    var isTransitioning by remember { mutableStateOf(false) }
    
    // Track media changes for animations
    LaunchedEffect(mediaMetadata?.id) {
        if (previousMediaId != null && previousMediaId != mediaMetadata?.id) {
            isTransitioning = true
            delay(1000) // Animation duration
            isTransitioning = false
        }
        previousMediaId = mediaMetadata?.id
    }
    
    // Grid state
    val thumbnailLazyGridState = rememberLazyGridState()
    
    // Create a playlist using correct shuffle-aware logic
    val timeline = playerConnection.player.currentTimeline
    val currentIndex = playerConnection.player.currentMediaItemIndex
    val shuffleModeEnabled = playerConnection.player.shuffleModeEnabled
    
    // Create a comprehensive list of media items with 5 past and 5 future items for preloading
    val mediaItems = remember(currentIndex, timeline, swipeThumbnail) {
        if (!swipeThumbnail || timeline.isEmpty) {
            val currentMediaItem = try {
                playerConnection.player.currentMediaItem
            } catch (e: Exception) { null }
            listOfNotNull(currentMediaItem)
        } else {
            val items = mutableListOf<MediaItem>()
            
            // Add 5 previous items
            var tempIndex = currentIndex
            repeat(5) {
                val previousIndex = timeline.getPreviousWindowIndex(
                    tempIndex,
                    Player.REPEAT_MODE_OFF,
                    shuffleModeEnabled
                )
                if (previousIndex != C.INDEX_UNSET) {
                    try {
                        val mediaItem = playerConnection.player.getMediaItemAt(previousIndex)
                        if (mediaItem != null) {
                            items.add(0, mediaItem)
                            tempIndex = previousIndex
                        }
                    } catch (e: Exception) {
                        // Continue if we can't get this item
                    }
                }
            }
            
            // Add current item
            val currentMediaItem = try {
                playerConnection.player.currentMediaItem
            } catch (e: Exception) { null }
            if (currentMediaItem != null) {
                items.add(currentMediaItem)
            }
            
            // Add 5 next items
            tempIndex = currentIndex
            repeat(5) {
                val nextIndex = timeline.getNextWindowIndex(
                    tempIndex,
                    Player.REPEAT_MODE_OFF,
                    shuffleModeEnabled
                )
                if (nextIndex != C.INDEX_UNSET) {
                    try {
                        val mediaItem = playerConnection.player.getMediaItemAt(nextIndex)
                        if (mediaItem != null) {
                            items.add(mediaItem)
                            tempIndex = nextIndex
                        }
                    } catch (e: Exception) {
                        // Continue if we can't get this item
                    }
                }
            }
            
            items
        }
    }
    
    val currentMediaIndex = mediaItems.indexOfFirst { 
        it.mediaId == playerConnection.player.currentMediaItem?.mediaId 
    }.takeIf { it >= 0 } ?: 0

    // OuterTune Snap behavior
    val horizontalLazyGridItemWidthFactor = 1f
    val thumbnailSnapLayoutInfoProvider = remember(thumbnailLazyGridState) {
        SnapLayoutInfoProvider(
            lazyGridState = thumbnailLazyGridState,
            positionInLayout = { layoutSize, itemSize ->
                (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
            },
            velocityThreshold = 500f
        )
    }

    // Current item tracking
    val currentItem by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemIndex } }
    val itemScrollOffset by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemScrollOffset } }

    // Handle swipe to change song
    LaunchedEffect(itemScrollOffset) {
        if (!thumbnailLazyGridState.isScrollInProgress || !swipeThumbnail || itemScrollOffset != 0 || currentMediaIndex < 0) return@LaunchedEffect

        if (currentItem > currentMediaIndex && canSkipNext) {
            playerConnection.player.seekToNext()
        } else if (currentItem < currentMediaIndex && canSkipPrevious) {
            playerConnection.player.seekToPreviousMediaItem()
        }
    }
    
    // Update media items when current song changes to ensure proper preloading
    LaunchedEffect(playerConnection.player.currentMediaItemIndex) {
        // This will trigger a recomposition of the mediaItems remember block
        // which will rebuild the list with the new current song and its surrounding items
    }

    // Update position when song changes
    LaunchedEffect(mediaMetadata, canSkipPrevious, canSkipNext) {
        val index = maxOf(0, currentMediaIndex)
        if (index >= 0 && index < mediaItems.size) {
            try {
                thumbnailLazyGridState.animateScrollToItem(index)
            } catch (e: Exception) {
                thumbnailLazyGridState.scrollToItem(index)
            }
        }
    }

    LaunchedEffect(playerConnection.player.currentMediaItemIndex) {
        val index = mediaItems.indexOfFirst { 
            it.mediaId == playerConnection.player.currentMediaItem?.mediaId 
        }
        if (index >= 0 && index != currentItem) {
            thumbnailLazyGridState.scrollToItem(index)
        }
    }

    // Keep screen on when lyrics are shown
    DisposableEffect(showLyrics) {
        currentView.keepScreenOn = showLyrics
        onDispose { currentView.keepScreenOn = false }
    }

    // Seek on double tap
    var showSeekEffect by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }
    val layoutDirection = LocalLayoutDirection.current

    Box(modifier = modifier) {
        // Lyrics view
        AnimatedVisibility(
            visible = showLyrics && error == null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box { Lyrics(sliderPositionProvider = sliderPositionProvider, navController = navController) }
        }

        // Error view
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(32.dp)
                .align(Alignment.Center),
        ) {
            error?.let { playbackError ->
                PlaybackError(
                    error = playbackError,
                    retry = playerConnection.player::prepare,
                )
            }
        }

        // Main thumbnail view
        AnimatedVisibility(
            visible = !showLyrics && error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            BoxWithConstraints(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor
                val containerMaxWidth = maxWidth

                LazyHorizontalGrid(
                    state = thumbnailLazyGridState,
                    rows = GridCells.Fixed(1),
                    flingBehavior = rememberSnapFlingBehavior(thumbnailSnapLayoutInfoProvider),
                    userScrollEnabled = swipeThumbnail,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = mediaItems,
                        key = { it.mediaId }
                    ) { item ->
                        Box(
                            modifier = Modifier
                                .width(horizontalLazyGridItemWidth)
                                .fillMaxSize()
                                .padding(horizontal = PlayerHorizontalPadding)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            // Toggle lyrics on single tap
                                            coroutineScope.launch {
                                                context.dataStore.edit { preferences ->
                                                    preferences[ShowLyricsKey] = !(preferences[ShowLyricsKey] ?: false)
                                                }
                                            }
                                        },
                                        onDoubleTap = { offset ->
                                            val currentPosition = playerConnection.player.currentPosition
                                            if ((layoutDirection == LayoutDirection.Ltr && offset.x < size.width / 2) ||
                                                (layoutDirection == LayoutDirection.Rtl && offset.x > size.width / 2)
                                            ) {
                                                playerConnection.player.seekTo(
                                                    (currentPosition - 5000).coerceAtLeast(0)
                                                )
                                                seekDirection = context.getString(R.string.seek_backward)
                                            } else {
                                                playerConnection.player.seekTo(
                                                    (currentPosition + 5000).coerceAtMost(
                                                        playerConnection.player.duration
                                                    )
                                                )
                                                seekDirection = context.getString(R.string.seek_forward)
                                            }
                                            showSeekEffect = true
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .size(containerMaxWidth - (PlayerHorizontalPadding * 2))
                            ) {
                                // Animated album art with zoom and pan effects
                                val animatedScale by animateFloatAsState(
                                    targetValue = if (isTransitioning) 1.1f else 1.0f,
                                    animationSpec = tween(1000, easing = EaseInOutCubic),
                                    label = "album_art_scale"
                                )
                                
                                val animatedRotation by animateFloatAsState(
                                    targetValue = if (isTransitioning) 5f else 0f,
                                    animationSpec = tween(1000, easing = EaseInOutCubic),
                                    label = "album_art_rotation"
                                )
                                
                                AsyncImage(
                                    model = item.mediaMetadata.artworkUri?.toString(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(ThumbnailCornerRadius * 2))
                                        .graphicsLayer(
                                            scaleX = animatedScale,
                                            scaleY = animatedScale,
                                            rotationZ = animatedRotation,
                                            renderEffect = BlurEffect(radiusX = 75f, radiusY = 75f),
                                            alpha = 0.5f
                                        )
                                )

                                // Main image with enhanced shadow for 3D effect and animations
                                val mainImageScale by animateFloatAsState(
                                    targetValue = if (isTransitioning) 1.05f else 1.0f,
                                    animationSpec = tween(1000, easing = EaseInOutCubic),
                                    label = "main_image_scale"
                                )
                                
                                val mainImageRotation by animateFloatAsState(
                                    targetValue = if (isTransitioning) 2f else 0f,
                                    animationSpec = tween(1000, easing = EaseInOutCubic),
                                    label = "main_image_rotation"
                                )
                                
                                AsyncImage(
                                    model = item.mediaMetadata.artworkUri?.toString(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(ThumbnailCornerRadius * 2))
                                        .graphicsLayer(
                                            scaleX = mainImageScale,
                                            scaleY = mainImageScale,
                                            rotationZ = mainImageRotation
                                        )
                                        .shadow(
                                            elevation = 32.dp,
                                            shape = RoundedCornerShape(ThumbnailCornerRadius * 2),
                                            ambientColor = Color.Black.copy(alpha = 0.6f),
                                            spotColor = Color.Black.copy(alpha = 0.8f)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Seek effect
        LaunchedEffect(showSeekEffect) {
            if (showSeekEffect) {
                delay(1000)
                showSeekEffect = false
            }
        }

        AnimatedVisibility(
            visible = showSeekEffect,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = seekDirection,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            )
        }
    }
}

/*
 * Copyright (C) OuterTune Project
 * Custom SnapLayoutInfoProvider idea belongs to OuterTune
 */

// SnapLayoutInfoProvider
@ExperimentalFoundationApi
fun SnapLayoutInfoProvider(
    lazyGridState: LazyGridState,
    positionInLayout: (layoutSize: Float, itemSize: Float) -> Float = { layoutSize, itemSize ->
        (layoutSize / 2f - itemSize / 2f)
    },
    velocityThreshold: Float = 1000f,
): SnapLayoutInfoProvider = object : SnapLayoutInfoProvider {
    private val layoutInfo: LazyGridLayoutInfo
        get() = lazyGridState.layoutInfo

    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f
    override fun calculateSnapOffset(velocity: Float): Float {
        val bounds = calculateSnappingOffsetBounds()

        // Only snap when velocity exceeds threshold
        if (abs(velocity) < velocityThreshold) {
            if (abs(bounds.start) < abs(bounds.endInclusive))
                return bounds.start

            return bounds.endInclusive
        }

        return when {
            velocity < 0 -> bounds.start
            velocity > 0 -> bounds.endInclusive
            else -> 0f
        }
    }

    fun calculateSnappingOffsetBounds(): ClosedFloatingPointRange<Float> {
        var lowerBoundOffset = Float.NEGATIVE_INFINITY
        var upperBoundOffset = Float.POSITIVE_INFINITY

        layoutInfo.visibleItemsInfo.fastForEach { item ->
            val offset = calculateDistanceToDesiredSnapPosition(layoutInfo, item, positionInLayout)

            // Find item that is closest to the center
            if (offset <= 0 && offset > lowerBoundOffset) {
                lowerBoundOffset = offset
            }

            // Find item that is closest to center, but after it
            if (offset >= 0 && offset < upperBoundOffset) {
                upperBoundOffset = offset
            }
        }

        return lowerBoundOffset.rangeTo(upperBoundOffset)
    }
}

fun calculateDistanceToDesiredSnapPosition(
    layoutInfo: LazyGridLayoutInfo,
    item: LazyGridItemInfo,
    positionInLayout: (layoutSize: Float, itemSize: Float) -> Float,
): Float {
    val containerSize =
        layoutInfo.singleAxisViewportSize - layoutInfo.beforeContentPadding - layoutInfo.afterContentPadding

    val desiredDistance = positionInLayout(containerSize.toFloat(), item.size.width.toFloat())
    val itemCurrentPosition = item.offset.x.toFloat()

    return itemCurrentPosition - desiredDistance
}

private val LazyGridLayoutInfo.singleAxisViewportSize: Int
    get() = if (orientation == Orientation.Vertical) viewportSize.height else viewportSize.width
