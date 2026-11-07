package org.sonora.android.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same cases as the computer's drive.rs tests: both sides must merge alike. */
class DriveSyncMergeTest {
    private fun t(id: String) = JsonObject().apply { addProperty("id", id) }
    private fun p(id: String, name: String, at: Long) = JsonObject().apply {
        addProperty("id", id); addProperty("name", name); addProperty("updatedAt", at)
    }
    private fun arr(vararg e: JsonObject) = JsonArray().apply { e.forEach { add(it) } }
    private fun ids(a: JsonArray) = a.map { it.asJsonObject["id"].asString }
    private val keepLocal: (com.google.gson.JsonElement, com.google.gson.JsonElement) -> Boolean = { _, _ -> false }

    @Test
    fun theFirstSyncJoinsBothLibraries() {
        val m = LibraryMerge.mergeList(emptySet(), arr(t("a"), t("b")), arr(t("b"), t("c")), keepLocal)
        assertEquals(listOf("a", "b", "c"), ids(m))
    }

    @Test
    fun aDeletionOnEitherSideWinsOverTheBase() {
        val m = LibraryMerge.mergeList(setOf("a", "b", "c"), arr(t("a"), t("b"), t("d")), arr(t("a"), t("c")), keepLocal)
        assertEquals(listOf("a", "d"), ids(m))
    }

    @Test
    fun theNewerEditOfAPlaylistWins() {
        val m = LibraryMerge.mergeList(setOf("p"), arr(p("p", "Old", 10)), arr(p("p", "Renamed", 20))) { r, l ->
            r.asJsonObject["updatedAt"].asLong > l.asJsonObject["updatedAt"].asLong
        }
        assertEquals("Renamed", m[0].asJsonObject["name"].asString)
    }

    @Test
    fun historyJoinsWithoutRepeats() {
        assertEquals(listOf("x", "y", "z"), ids(LibraryMerge.mergeHistory(arr(t("x"), t("y")), arr(t("y"), t("z")))))
    }
}
