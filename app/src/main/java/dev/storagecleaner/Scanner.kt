package dev.storagecleaner

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/*
 * Pure Kotlin/JVM: no Android APIs here, so ScannerTest runs on a normal JVM.
 * Same safety model as the Windows app:
 *   SAFE       rebuildable (thumbnail cache, temp, logs, app caches)
 *   CAUTION    deletable, but you lose something (the note says what)
 *   PROTECTED  never deleted (whole standard folders like DCIM, Android/media, .nomedia files)
 */

enum class Level { SAFE, CAUTION, PROTECTED }

data class Item(
    val category: String,
    val label: String,
    val level: Level,
    val note: String,
    val targets: List<File>,        // files or folders this item deletes
    val bytes: Long,
    val dirsOnly: Boolean = false,  // empty-folder items: remove folders, never a file added since the scan
) {
    val id = "$category|${targets.firstOrNull()?.path ?: label}" // computed once: used as list key and selection key
}

data class ScanResult(val items: List<Item>, val byType: Map<String, Long>, val files: Int, val bytes: Long)

data class Cleanup(val time: Long, val bytes: Long, val items: Int, val source: String)

const val MB = 1024L * 1024
const val DAY = 86_400_000L

private val STANDARD_DIRS = setOf("android", "dcim", "pictures", "download", "documents", "music", "movies",
    "alarms", "notifications", "podcasts", "ringtones", "recordings", "audiobooks", "screenshots")
private val NEVER_WHOLE = setOf("android/data", "android/media", "android/obb")
private val CACHE_DIRS = setOf("cache", ".cache", "temp", "tmp", ".tmp")
private val TEMP_EXTS = setOf("tmp", "temp", "log", "dmp")
private val APK_EXTS = setOf("apk", "apks", "xapk")
private val APP_DIR_KINDS = setOf("data", "media", "obb")
private val CHAT_ROOTS = listOf( // where chat apps keep received media, newest layout first
    "android/media/com.whatsapp/whatsapp/media" to "WhatsApp",
    "android/media/com.whatsapp.w4b/whatsapp business/media" to "WhatsApp Business",
    "whatsapp/media" to "WhatsApp",
    "android/media/org.telegram.messenger/telegram" to "Telegram",
    "telegram" to "Telegram",
)
private val TYPES = mapOf(
    "Images" to setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng"),
    "Videos" to setOf("mp4", "mkv", "mov", "3gp", "webm", "avi", "m4v"),
    "Audio" to setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr"),
    "Documents" to setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv"),
    "Archives & APKs" to setOf("zip", "rar", "7z", "tar", "gz", "apk", "apks", "xapk"),
)

fun fmt(n: Long): String {
    var v = n.toDouble()
    for (unit in listOf("B", "KB", "MB", "GB")) {
        if (v < 1024) return if (unit == "B") "${n} B" else "%.1f %s".format(v, unit)
        v /= 1024
    }
    return "%.1f TB".format(v)
}

class Scanner(
    root: File,
    private val isInstalled: (String) -> Boolean = { true },
    private val apkNote: (File) -> String? = { null },
    private val now: Long = System.currentTimeMillis(),
    private val largeBytes: Long = 100 * MB,
) {
    private val root = root.absoluteFile.normalize()
    private val rootPath = this.root.path

    private class Entry(val file: File, val rel: String, val size: Long, val mtime: Long)

    private fun relOf(path: String): String {
        val r = if (path.length > rootPath.length) path.substring(rootPath.length + 1) else ""
        return if (File.separatorChar == '/') r else r.replace(File.separatorChar, '/')
    }

    fun rel(f: File): String = relOf(f.absoluteFile.normalize().path)

    /** "Android/media/com.x/..." -> "Android/media/com.x" (the per-app folder), else null. */
    private fun appDirOf(rel: String): String? {
        if (!rel.startsWith("android/", ignoreCase = true)) return null
        val a = rel.indexOf('/', 8)
        if (a < 0 || rel.substring(8, a).lowercase() !in APP_DIR_KINDS) return null
        val b = rel.indexOf('/', a + 1)
        return if (b < 0) null else rel.substring(0, b)
    }

    /** One walk; never follows links. Returns all files and the top-most folders that hold no file at all. */
    private fun walk(): Pair<List<Entry>, List<File>> {
        val files = ArrayList<Entry>()
        val empty = ArrayList<File>()
        val hasContent = ArrayDeque<Boolean>()
        val emptyKids = ArrayDeque<MutableList<File>>()
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                hasContent.addLast(false)
                emptyKids.addLast(ArrayList())
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (hasContent.isNotEmpty()) hasContent[hasContent.lastIndex] = true // links and specials count as content
                if (attrs.isRegularFile) {
                    val f = file.toFile()
                    files += Entry(f, relOf(file.toString()), attrs.size(), attrs.lastModifiedTime().toMillis())
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                if (hasContent.isNotEmpty()) hasContent[hasContent.lastIndex] = true // unreadable: assume content
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                val content = hasContent.removeLast() || exc != null
                val kids = emptyKids.removeLast()
                if (content) empty += kids // an empty parent already covers its empty children
                if (hasContent.isNotEmpty()) {
                    if (content) hasContent[hasContent.lastIndex] = true else emptyKids.last() += dir.toFile()
                }
                return FileVisitResult.CONTINUE
            }
        })
        return files to empty
    }

    fun scan(progress: (String) -> Unit = {}): ScanResult {
        progress("Reading files…")
        val (all, emptyDirs) = walk()
        val items = ArrayList<Item>()
        val groups = LinkedHashMap<Pair<String, String>, Triple<Level, String, MutableList<Entry>>>()
        fun put(cat: String, label: String, level: Level, note: String, e: Entry) =
            groups.getOrPut(cat to label) { Triple(level, note, ArrayList()) }.third.add(e)

        // Leftovers: app folders whose app is gone and that nobody touched for 30 days.
        progress("Looking for leftovers of removed apps…")
        val byAppDir = HashMap<String, MutableList<Entry>>()
        for (e in all) appDirOf(e.rel)?.let { byAppDir.getOrPut(it) { ArrayList() } += e }
        val leftover = HashSet<String>()
        for ((dir, es) in byAppDir) {
            val pkg = dir.substringAfterLast('/')
            if ('.' !in pkg || isInstalled(pkg) || es.maxOf { it.mtime } > now - 30 * DAY) continue
            leftover += dir.lowercase()
            items += Item("Leftovers", pkg, Level.CAUTION, "This app isn't installed anymore; unused for 30+ days",
                listOf(File(root, dir)), es.sumOf { it.size })
        }

        progress("Sorting junk by safety…")
        val rest = ArrayList<Entry>()
        for (e in all) {
            val lower = e.rel.lowercase()
            val parts = lower.split('/')
            val dirs = parts.dropLast(1)
            val name = parts.last()
            val ext = name.substringAfterLast('.', "")
            val age = now - e.mtime
            val chat = CHAT_ROOTS.firstOrNull { lower.startsWith(it.first + "/") }
            when {
                name == ".nomedia" -> {}
                leftover.isNotEmpty() && appDirOf(lower) in leftover -> {} // already counted in its Leftovers item
                dirs.size >= 4 && dirs[0] == "android" && dirs[1] == "data" && dirs[3] == "cache" ->
                    put("App caches", parts[2], Level.SAFE, "App cache; the app rebuilds it", e)
                ".thumbnails" in dirs -> put("Thumbnails", "Gallery thumbnail cache", Level.SAFE, "Gallery apps rebuild it", e)
                chat != null -> {
                    val segs = e.rel.split('/')
                    val at = chat.first.count { it == '/' } + 1
                    val sub = if (at < segs.size - 1) segs[at] else "Other"
                    if (sub.equals(".Statuses", true)) put("Chat media", "${chat.second} · viewed statuses", Level.SAFE, "Statuses you viewed; they expire after 24 h anyway", e)
                    else put("Chat media", "${chat.second} · $sub", Level.CAUTION, "Media you sent or received; gone unless saved elsewhere", e)
                }
                age > DAY && dirs.any { it in CACHE_DIRS } -> {
                    val i = dirs.indexOfFirst { it in CACHE_DIRS }
                    put("Temp & cache", e.rel.split('/').take(i + 1).joinToString("/"), Level.SAFE, "Cache/temp folder in shared storage; apps rebuild it", e)
                }
                age > DAY && (ext in TEMP_EXTS || name.endsWith(".log.1")) ->
                    put("Temp & logs", "Temporary and log files", Level.SAFE, "Leftover temp, log and crash files", e)
                dirs.firstOrNull() == "lost.dir" -> put("System", "LOST.DIR fragments", Level.CAUTION, "Pieces recovered after storage errors; rarely useful", e)
                ext in APK_EXTS -> items += Item("Installers", e.file.name, Level.CAUTION,
                    apkNote(e.file) ?: "App installer; you can reinstall from the store", listOf(e.file), e.size)
                dirs.firstOrNull() == "download" && age > 30 * DAY -> put("Downloads", "Downloads untouched for 30+ days", Level.CAUTION, "Your downloads: review before deleting", e)
                else -> rest += e
            }
        }
        for ((key, v) in groups) items += Item(key.first, key.second, v.first, v.second, v.third.map { it.file }, v.third.sumOf { it.size })

        progress("Comparing files to find duplicates…")
        val dupeTargets = HashSet<File>()
        for (same in duplicates(rest)) {
            val keep = same.minWith(compareBy<Entry> { it.mtime }.thenBy { it.rel.length })
            val extra = same - keep
            dupeTargets += extra.map { it.file }
            items += Item("Duplicates", keep.file.name, Level.CAUTION, "${same.size} identical copies; keeps ${keep.rel}",
                extra.map { it.file }, keep.size * extra.size)
        }
        for (e in rest) if (e.size >= largeBytes && e.file !in dupeTargets) {
            val days = (now - e.mtime) / DAY
            items += Item("Large files", e.file.name, Level.CAUTION, "${e.rel.substringBeforeLast('/', "/")} · ${if (days > 0) "$days days old" else "new"}",
                listOf(e.file), e.size)
        }

        val empties = emptyDirs.filter { d ->
            val r = rel(d).lowercase()
            r.isNotEmpty() && !r.startsWith("android") && r !in STANDARD_DIRS
        }
        if (empties.isNotEmpty()) items += Item("Empty folders", "${empties.size} empty folders", Level.SAFE,
            "Contain no files (only empty sub-folders, if any)", empties, 0, dirsOnly = true)

        val byType = all.groupBy { e -> e.rel.substringAfterLast('.', "").lowercase().let { x -> TYPES.entries.firstOrNull { x in it.value }?.key ?: "Other" } }
            .mapValues { (_, es) -> es.sumOf { it.size } }
        return ScanResult(items.filter { it.bytes > 0 || it.dirsOnly }.sortedByDescending { it.bytes }, byType, all.size, all.sumOf { it.size })
    }

    /** Groups of identical files: same size -> same first 64 KB -> same full SHA-256. */
    private fun duplicates(files: List<Entry>, minSize: Long = MB): List<List<Entry>> =
        files.filter { it.size >= minSize }.groupBy { it.size }.values.filter { it.size > 1 }
            .flatMap { same -> same.groupBy { hash(it.file, HEAD) }.filter { it.key != null }.values.filter { it.size > 1 } }
            .flatMap { same -> if (same[0].size <= HEAD) listOf(same) else same.groupBy { hash(it.file, Long.MAX_VALUE) }.filter { it.key != null }.values.filter { it.size > 1 } }

    private fun hash(f: File, limit: Long): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var left = limit
        f.inputStream().use { s ->
            while (left > 0) {
                val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                md.update(buf, 0, n)
                left -= n
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: IOException) {
        null // unreadable: never grouped
    }

    /** Hard guard, whatever an item says: inside the storage root, not a whole standard folder, never .nomedia. */
    fun canDelete(f: File): Boolean {
        val p = f.absoluteFile.normalize().path
        if (!p.startsWith(rootPath + File.separator)) return false
        val r = rel(f).lowercase()
        return r !in STANDARD_DIRS && r !in NEVER_WHOLE && f.name != ".nomedia"
    }

    /** Deletes every allowed target; returns bytes freed. Links are removed, never followed. */
    fun delete(items: List<Item>): Long = items.sumOf { item ->
        item.targets.sumOf { t -> if (canDelete(t)) deleteTree(t.toPath(), item.dirsOnly) else 0L }
    }

    private fun deleteTree(p: Path, dirsOnly: Boolean): Long {
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            var n = 0L
            try {
                Files.newDirectoryStream(p).use { s -> for (c in s) n += deleteTree(c, dirsOnly) }
                Files.delete(p) // fails, harmlessly, if something inside was kept
            } catch (e: IOException) {
            }
            return n
        }
        if (dirsOnly) return 0L
        return try {
            val size = if (Files.isSymbolicLink(p)) 0L else Files.size(p)
            Files.delete(p) // on a link this removes the link itself
            size
        } catch (e: IOException) {
            0L
        }
    }

    private companion object {
        const val HEAD = 64L * 1024
    }
}

/** Cleanup log: one tab-separated line per run. */
class History(private val file: File) {
    fun add(c: Cleanup) {
        file.parentFile?.mkdirs()
        file.appendText("${c.time}\t${c.bytes}\t${c.items}\t${c.source.replace('\t', ' ')}\n")
    }

    fun all(): List<Cleanup> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 4) null else Cleanup(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toLongOrNull() ?: 0, p[2].toIntOrNull() ?: 0, p[3])
    }.reversed()
}
