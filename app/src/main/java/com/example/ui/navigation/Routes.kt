package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object ImportMovieRoute

@Serializable
data class DubbingSettingsRoute(val projectId: Long)

@Serializable
data class AudioSettingsRoute(val projectId: Long)

@Serializable
data class ProcessingRoute(val projectId: Long)

@Serializable
data class ReviewRoute(val projectId: Long)

@Serializable
data class ExportRoute(val projectId: Long)

@Serializable
data object SettingsRoute

@Serializable
data object SlangDictionaryRoute
