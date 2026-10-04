/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.browser.folderlist

import app.infinity.mpvz.domain.media.model.VideoFolder
import app.infinity.mpvz.preferences.BlacklistScope
import app.infinity.mpvz.preferences.FolderBlacklistMatcher
import app.infinity.mpvz.preferences.FoldersPreferences
import app.infinity.mpvz.preferences.preference.Preference
import app.infinity.mpvz.preferences.preference.PreferenceStore
import app.infinity.mpvz.ui.browser.selection.SelectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderBlacklistInversionTest {
  @Test
  fun invertingOneSelectedFolderBlacklistsOnlyItsComplement() {
    val outcome = invertAndPersist(setOf(PARENT))

    assertEquals(setOf(CHILD_A, CHILD_B, OTHER), outcome.persistedBlacklist)
    assertFalse(PARENT in outcome.persistedBlacklist)
    assertEquals(setOf(PARENT), outcome.visiblePaths)
  }

  @Test
  fun invertingTwoSelectedChildFoldersDoesNotBlacklistThemViaTheirParent() {
    val selectedPaths = setOf(CHILD_A, CHILD_B)
    val outcome = invertAndPersist(selectedPaths)

    // SelectionState inverts to the parent and the other child. Persisting those paths must not
    // implicitly blacklist either selected child.
    assertEquals(setOf(PARENT, OTHER), outcome.persistedBlacklist)
    assertTrue(selectedPaths.intersect(outcome.persistedBlacklist).isEmpty())
    assertEquals(selectedPaths, outcome.visiblePaths)
  }

  @Test
  fun selectAllAndClearOperateOnTheCurrentFolderIds() {
    val allPaths = listOf(PARENT, CHILD_A, CHILD_B, OTHER)
    val selected = SelectionState<String>().selectAll(allPaths)

    assertEquals(allPaths.toSet(), selected.selectedIds)
    assertEquals(emptySet<String>(), selected.clear().selectedIds)
  }

  @Test
  fun audioBlacklistDoesNotHideMediaInNestedChildFolders() {
    assertTrue(
      FolderBlacklistMatcher.isMediaFileBlacklisted(
        "$PARENT/direct-track.mp3",
        setOf(PARENT),
      ),
    )
    assertFalse(
      FolderBlacklistMatcher.isMediaFileBlacklisted(
        "$CHILD_A/nested-track.mp3",
        setOf(PARENT),
      ),
    )
  }

  @Test
  fun repeatedScopeChangesAndReloadsKeepVideoAndAudioSetsConsistent() {
    val store = InMemoryPreferenceStore()
    val preferences = FoldersPreferences(store)

    preferences.addBlacklistedFolders(setOf(CHILD_A), BlacklistScope.BOTH)
    preferences.addBlacklistedFolders(setOf(CHILD_A), BlacklistScope.AUDIO_ONLY)
    preferences.addBlacklistedFolders(setOf(CHILD_A), BlacklistScope.VIDEO_ONLY)
    preferences.addBlacklistedFolders(setOf(CHILD_A), BlacklistScope.VIDEO_ONLY)

    val reloadedPreferences = FoldersPreferences(store)
    assertEquals(setOf(CHILD_A), reloadedPreferences.blacklistedFolders.get())
    assertTrue(reloadedPreferences.blacklistedAudioFolders.get().isEmpty())

    reloadedPreferences.addBlacklistedFolders(setOf(CHILD_B), BlacklistScope.BOTH)
    reloadedPreferences.removeBlacklistedFolders(setOf(CHILD_B))
    assertEquals(setOf(CHILD_A), reloadedPreferences.blacklistedFolders.get())
    assertTrue(reloadedPreferences.blacklistedAudioFolders.get().isEmpty())

    reloadedPreferences.removeBlacklistedFolder(CHILD_A)
    reloadedPreferences.addBlacklistedFolders(setOf(CHILD_B), BlacklistScope.BOTH)
    reloadedPreferences.clearAllBlacklistedFolders()
    assertTrue(reloadedPreferences.blacklistedFolders.get().isEmpty())
    assertTrue(reloadedPreferences.blacklistedAudioFolders.get().isEmpty())
  }

  private fun invertAndPersist(selectedPaths: Set<String>): Outcome {
    val folders =
      listOf(PARENT, CHILD_A, CHILD_B, OTHER).map { path ->
        VideoFolder(
          bucketId = path,
          name = path.substringAfterLast('/'),
          path = path,
          videoCount = 1,
        )
      }
    val invertedSelection =
      SelectionState(selectedIds = selectedPaths)
        .invertSelection(folders.map { it.bucketId })
    val invertedPaths =
      invertedSelection
        .getSelected(folders) { it.bucketId }
        .mapTo(linkedSetOf()) { it.path }

    val preferences = FoldersPreferences(InMemoryPreferenceStore())
    preferences.addBlacklistedFolders(invertedPaths, BlacklistScope.VIDEO_ONLY)
    val persistedBlacklist = preferences.blacklistedFolders.get()

    assertEquals(invertedPaths, persistedBlacklist)
    assertTrue(preferences.blacklistedAudioFolders.get().isEmpty())

    val visiblePaths =
      folders
        .filterNot { folder -> FolderBlacklistMatcher.isBlacklisted(folder.path, persistedBlacklist) }
        .mapTo(linkedSetOf()) { it.path }
    return Outcome(persistedBlacklist, visiblePaths)
  }

  private data class Outcome(
    val persistedBlacklist: Set<String>,
    val visiblePaths: Set<String>,
  )

  private class InMemoryPreferenceStore : PreferenceStore {
    private val values = mutableMapOf<String, Any>()

    override fun getString(
      key: String,
      defaultValue: String,
    ): Preference<String> = preference(key, defaultValue)

    override fun getLong(
      key: String,
      defaultValue: Long,
    ): Preference<Long> = preference(key, defaultValue)

    override fun getInt(
      key: String,
      defaultValue: Int,
    ): Preference<Int> = preference(key, defaultValue)

    override fun getFloat(
      key: String,
      defaultValue: Float,
    ): Preference<Float> = preference(key, defaultValue)

    override fun getBoolean(
      key: String,
      defaultValue: Boolean,
    ): Preference<Boolean> = preference(key, defaultValue)

    override fun getStringSet(
      key: String,
      defaultValue: Set<String>,
    ): Preference<Set<String>> = preference(key, defaultValue)

    override fun <T> getObject(
      key: String,
      defaultValue: T,
      serializer: (T) -> String,
      deserializer: (String) -> T,
    ): Preference<T> = preference(key, defaultValue)

    override fun getAll(): Map<String, *> = values.toMap()

    private fun <T> preference(
      key: String,
      defaultValue: T,
    ): Preference<T> =
      object : Preference<T> {
        override fun key(): String = key

        @Suppress("UNCHECKED_CAST")
        override fun get(): T = values[key] as? T ?: defaultValue

        override fun set(value: T) {
          values[key] = value as Any
        }

        override fun isSet(): Boolean = key in values

        override fun delete() {
          values.remove(key)
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = flowOf(get())

        override fun stateIn(scope: CoroutineScope): StateFlow<T> =
          changes().stateIn(scope, SharingStarted.Eagerly, get())
      }
  }

  private companion object {
    const val PARENT = "/storage/emulated/0/Movies"
    const val CHILD_A = "$PARENT/Keep A"
    const val CHILD_B = "$PARENT/Keep B"
    const val OTHER = "$PARENT/Other"
  }
}
