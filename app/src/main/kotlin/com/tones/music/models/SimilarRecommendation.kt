package com.tones.music.models

import com.tones.innertube.models.YTItem
import com.tones.music.db.entities.LocalItem

data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<YTItem>,
)
