package dev.storagecleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class ScannerTest {
    private val now = System.currentTimeMillis()

    private fun File.put(rel: String, bytes: ByteArray = ByteArray(10), ageDays: Int = 0): File =
        File(this, rel).also { it.parentFile.mkdirs(); it.writeBytes(bytes); it.setLastModified(now - ageDays * DAY) }

    @Test
    fun scanSortsDeletesAndGuards() {
        val root = Files.createTempDirectory("sc").toFile()
        val outside = Files.createTempDirectory("outside").toFile().put("precious.txt")
        val photo = Random(1).nextBytes(1_500_000)
        root.put("DCIM/.thumbnails/t1.jpg", ByteArray(500))
        root.put("DCIM/Camera/photo.jpg", photo, ageDays = 10)
        root.put("Download/photo (1).jpg", photo, ageDays = 2)          // duplicate, newer: the one removed
        root.put("Pictures/near.jpg", photo.copyOf().also { it[it.size - 1] = 7 }) // same size + head, different tail
        root.put("Download/old.pdf", ageDays = 40)
        root.put("Download/new.pdf")
        root.put("Download/game.apk", ByteArray(20))
        root.put("Some/cache/blob.bin", ageDays = 2)
        root.put("Some/cache/fresh.bin")                                // < 1 day old: kept
        root.put("Some/.nomedia", ByteArray(0))
        root.put("Android/media/com.gone.app/x.bin", ByteArray(50), ageDays = 60)
        root.put("Android/media/com.kept.app/x.bin", ByteArray(50), ageDays = 60)
        root.put("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/v.mp4", ByteArray(70))
        root.put("Android/media/com.whatsapp/WhatsApp/Media/.Statuses/s.jpg", ByteArray(30))
        File(root, "Old/Empty/Deeper").mkdirs()
        File(root, "Music").mkdirs()                                     // standard folder: never "empty junk"
        val link = try { Files.createSymbolicLink(File(root, "Some/cache/link").toPath(), outside.parentFile.toPath()); true } catch (e: Exception) { false }

        val sc = Scanner(root, isInstalled = { it != "com.gone.app" }, apkNote = { "note:${it.name}" }, now = now, largeBytes = 1_000_000)
        val items = sc.scan().items
        val byCat = items.groupBy { it.category }
        fun one(cat: String) = byCat.getValue(cat).single()

        assertEquals(Level.SAFE, one("Thumbnails").level)
        assertEquals(listOf("com.gone.app"), byCat.getValue("Leftovers").map { it.label })
        assertEquals("note:game.apk", one("Installers").note)
        assertEquals(listOf("old.pdf"), one("Downloads").targets.map { it.name })
        assertEquals(listOf("blob.bin"), one("Temp & cache").targets.map { it.name })
        val dupe = one("Duplicates")
        assertEquals(listOf("photo (1).jpg"), dupe.targets.map { it.name })
        assertTrue(dupe.note, dupe.note.endsWith("DCIM/Camera/photo.jpg"))
        assertEquals(listOf("near.jpg", "photo.jpg").sorted(), byCat.getValue("Large files").map { it.label }.sorted())
        val chat = byCat.getValue("Chat media").associateBy { it.label }
        assertEquals(Level.CAUTION, chat.getValue("WhatsApp · WhatsApp Video").level)
        assertEquals(Level.SAFE, chat.getValue("WhatsApp · viewed statuses").level)
        val empty = one("Empty folders")
        assertEquals(listOf("Old"), empty.targets.map { it.name })
        assertTrue(empty.dirsOnly)

        // guard: never the root, whole standard folders, .nomedia or anything outside
        assertFalse(sc.canDelete(root))
        assertFalse(sc.canDelete(File(root, "DCIM")))
        assertFalse(sc.canDelete(File(root, "Android/media")))
        assertFalse(sc.canDelete(File(root, "Some/.nomedia")))
        assertFalse(sc.canDelete(outside))
        assertFalse(sc.canDelete(File(root, "../" + outside.parentFile.name + "/precious.txt")))

        // empty-folder items never delete a file that appeared after the scan
        File(root, "Old/Empty/new.txt").writeText("hi")
        assertEquals(0L, sc.delete(listOf(empty)))
        assertTrue(File(root, "Old/Empty/new.txt").exists())

        val freed = sc.delete(items.filter { it.level == Level.SAFE || it.category in setOf("Duplicates", "Leftovers") })
        assertTrue(freed > 0)
        assertFalse(File(root, "Download/photo (1).jpg").exists())
        assertTrue(File(root, "DCIM/Camera/photo.jpg").exists())       // duplicates keep one copy
        assertFalse(File(root, "Android/media/com.gone.app").exists())
        assertTrue(File(root, "Android/media/com.kept.app/x.bin").exists())
        assertTrue(File(root, "Some/cache/fresh.bin").exists())
        assertTrue(File(root, "Some/.nomedia").exists())
        assertTrue("followed a link", outside.exists())
        if (link) assertTrue(File(root, "Some/cache/link").exists()) // not in any item: links are never collected
    }

    @Test
    fun historyRoundTripNewestFirst() {
        val h = History(File(Files.createTempDirectory("h").toFile(), "sub/history.tsv"))
        assertEquals(emptyList<Cleanup>(), h.all())
        h.add(Cleanup(1, 100, 2, "Manual"))
        h.add(Cleanup(2, 50, 1, "Weekly\tx"))
        assertEquals(listOf(2L, 1L), h.all().map { it.time })
        assertEquals("Weekly x", h.all()[0].source)
    }

    @Test
    fun formatsSizes() {
        assertEquals("512 B", fmt(512))
        assertEquals("1.5 MB", fmt(1_572_864).replace(',', '.'))
    }
}
