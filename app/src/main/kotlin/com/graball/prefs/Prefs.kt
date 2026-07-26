package com.graball.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("prefs")

/** One flat DataStore for all user settings. Keys stay private; API is typed accessors. */
object Prefs {
    // search engines: id -> query URL template
    val SEARCH_ENGINES = linkedMapOf(
        "google" to "https://www.google.com/search?hl=en&q=",
        "ddg" to "https://duckduckgo.com/?q=",
        "brave" to "https://search.brave.com/search?q=",
    )

    private val SEARCH = stringPreferencesKey("search_engine")
    private val ADBLOCK = booleanPreferencesKey("adblock")
    private val HTTPS_ONLY = booleanPreferencesKey("https_only")
    private val THEME = stringPreferencesKey("theme") // system | light | dark
    private fun folderKey(kind: String) = stringPreferencesKey("folder_$kind") // video|audio|image|other

    fun searchEngine(c: Context): Flow<String> = c.store.data.map { it[SEARCH] ?: "google" }
    suspend fun setSearchEngine(c: Context, id: String) = c.store.edit { it[SEARCH] = id }

    fun adblock(c: Context): Flow<Boolean> = c.store.data.map { it[ADBLOCK] ?: true }
    suspend fun setAdblock(c: Context, on: Boolean) = c.store.edit { it[ADBLOCK] = on }

    fun httpsOnly(c: Context): Flow<Boolean> = c.store.data.map { it[HTTPS_ONLY] ?: false }
    suspend fun setHttpsOnly(c: Context, on: Boolean) = c.store.edit { it[HTTPS_ONLY] = on }

    fun theme(c: Context): Flow<String> = c.store.data.map { it[THEME] ?: "system" }
    suspend fun setTheme(c: Context, mode: String) = c.store.edit { it[THEME] = mode }

    /** SAF tree URI per media kind ("video"|"audio"|"image"|"other"); null = MediaStore default. */
    fun folderFor(c: Context, kind: String): Flow<String?> = c.store.data.map { it[folderKey(kind)] }
    suspend fun setFolder(c: Context, kind: String, treeUri: String?) = c.store.edit {
        if (treeUri == null) it.remove(folderKey(kind)) else it[folderKey(kind)] = treeUri
    }
}
