package org.sonora.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DurableJsonFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun target(): File = File(folder.root, "favorites.json")

    private fun parse(text: String): String? {
        require(text.startsWith("[")) { "not a list" }
        return text
    }

    private fun corruptCopies(): List<File> =
        folder.root.listFiles().orEmpty().filter { it.name.contains(".corrupt-") }

    @Test
    fun `write keeps the previous version as backup`() {
        val file = target()
        DurableJsonFile.write(file, "[1]")
        DurableJsonFile.write(file, "[2]")

        assertEquals("[2]", file.readText())
        assertEquals("[1]", DurableJsonFile.backupOf(file).readText())
        assertFalse(File(folder.root, "favorites.json.tmp").exists())
    }

    @Test
    fun `read returns the main file when it is valid`() {
        val file = target()
        DurableJsonFile.write(file, "[1]")
        DurableJsonFile.write(file, "[2]")

        assertEquals("[2]", DurableJsonFile.read(file, ::parse))
        assertTrue(corruptCopies().isEmpty())
    }

    @Test
    fun `damaged file is recovered from backup and preserved`() {
        val file = target()
        DurableJsonFile.write(file, "[1]")
        DurableJsonFile.write(file, "[2]")
        file.writeText("{broken")

        assertEquals("[1]", DurableJsonFile.read(file, ::parse))
        assertEquals("{broken", corruptCopies().single().readText())
    }

    @Test
    fun `damaged file with no backup survives the next save`() {
        val file = target()
        file.writeText("{broken")

        assertNull(DurableJsonFile.read(file, ::parse))
        DurableJsonFile.write(file, "[new]")

        assertEquals("[new]", file.readText())
        assertEquals("{broken", corruptCopies().single().readText())
    }

    @Test
    fun `empty file falls back to backup`() {
        val file = target()
        DurableJsonFile.write(file, "[1]")
        DurableJsonFile.write(file, "[2]")
        file.writeText("")

        assertEquals("[1]", DurableJsonFile.read(file) { it.takeIf(String::isNotEmpty) })
        assertTrue(corruptCopies().isEmpty())
    }

    @Test
    fun `backup is used when a crash left no main file`() {
        val file = target()
        DurableJsonFile.write(file, "[1]")
        DurableJsonFile.write(file, "[2]")
        file.delete()

        assertEquals("[1]", DurableJsonFile.read(file, ::parse))
    }

    @Test
    fun `read returns null when nothing was ever saved`() {
        assertNull(DurableJsonFile.read(target(), ::parse))
    }

    @Test
    fun `only the most recent damaged copies are kept`() {
        val file = target()
        repeat(5) { index ->
            File(folder.root, "favorites.json.corrupt-100$index").writeText("old")
        }
        file.writeText("{broken")

        DurableJsonFile.read(file, ::parse)

        assertEquals(3, corruptCopies().size)
    }
}
