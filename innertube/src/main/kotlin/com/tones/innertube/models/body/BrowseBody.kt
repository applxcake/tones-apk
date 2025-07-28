package com.tones.innertube.models.body

import com.tones.innertube.models.Context
import com.tones.innertube.models.Continuation
import kotlinx.serialization.Serializable

@Serializable
data class BrowseBody(
    val context: Context,
    val browseId: String?,
    val params: String?,
    val continuation: String?
)
