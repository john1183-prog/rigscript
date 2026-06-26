package com.example.data

import kotlinx.serialization.json.Json

/**
 * Shared [Json] instances. Previously each of AppDatabase, AppRepository, and
 * MainViewModel created their own identical Json{} objects — three separate
 * caches and three sets of class-descriptor lookups for the same config.
 */
object AppJson {
    /** Compact — used for all database reads/writes. */
    val storage = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** Pretty-printed — used only for the script text editor display. */
    val pretty  = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
}
