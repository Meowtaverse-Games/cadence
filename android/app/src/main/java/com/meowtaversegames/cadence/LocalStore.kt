package com.meowtaversegames.cadence

import android.content.Context
import android.util.AtomicFile
import com.meowtaversegames.cadence.core.Timetable
import com.meowtaversegames.cadence.core.TimetableParser
import com.meowtaversegames.cadence.core.TimetableValue
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

class LocalStore(context: Context) {
    private val directory = context.filesDir
    private val timetable = LastGoodValue<CachedTimetable>()
    private val targets = LastGoodValue<Set<String>>()

    fun readTimetable(): ReadValue<CachedTimetable> = timetable.read {
        val text = read("timetable.json")
        if (text == null) CachedTimetable(null) else {
            CachedTimetable(requireNotNull(TimetableParser.parse(TimetableJson.decode(text)).timetable))
        }
    }

    fun readTargets(): ReadValue<Set<String>> = targets.read {
        val text = read("targets.json")
        if (text == null) emptySet() else {
            val array = TimetableJson.decode(text) as TimetableValue.ArrayValue
            array.values.map { (it as TimetableValue.StringValue).value.also { name ->
                require(name.isNotBlank() && name.none { char -> char.isWhitespace() })
            } }.toSet()
        }
    }

    fun saveTargets(packages: Set<String>) = write("targets.json", TimetableJson.encode(
        TimetableValue.ArrayValue(packages.sorted().map { TimetableValue.StringValue(it) }),
    ))

    /** An unreadable file is never treated as a missing file when saving. */
    @Synchronized
    fun readFavorites(): ReadValue<List<String>> = try {
        val text = read("favorites.json")
        val ids = if (text == null) emptyList() else {
            val array = TimetableJson.decode(text) as TimetableValue.ArrayValue
            array.values.map { (it as TimetableValue.StringValue).value.also { id ->
                require(id.isNotBlank() && '/' in id && id.none(Char::isWhitespace))
            } }.also { require(it.size <= FAVORITE_LIMIT && it.distinct().size == it.size) }
        }
        ReadValue(ids, false)
    } catch (_: Exception) {
        log("favorites_read_failed")
        ReadValue(emptyList(), true)
    }

    @Synchronized
    fun saveFavorites(ids: List<String>) {
        check(!readFavorites().failed) { "Unreadable favorites must be preserved" }
        require(ids.size <= FAVORITE_LIMIT && ids.distinct().size == ids.size)
        require(ids.all { it.isNotBlank() && '/' in it && it.none(Char::isWhitespace) })
        write("favorites.json", TimetableJson.encode(
            TimetableValue.ArrayValue(ids.map { TimetableValue.StringValue(it) }),
        ))
    }

    fun saveTimetable(value: TimetableValue) {
        require(TimetableParser.parse(value).valid)
        write("timetable.json", TimetableJson.encode(value))
    }

    private fun read(name: String): String? {
        val file = AtomicFile(File(directory, name))
        // AtomicFile may recover a backup even when its base file is missing.
        if (!file.baseFile.exists() && !File(directory, "$name.bak").exists()) return null
        return file.openRead().bufferedReader().use { it.readText() }
    }

    private fun write(name: String, text: String) {
        val file = AtomicFile(File(directory, name))
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    /** Only callers' fixed event names, permission flags and blocked package names are recorded. */
    fun log(event: String) {
        val entry = "${Instant.now()} $event"
        logWriter.execute { appendLog(entry) }
    }

    private fun appendLog(entry: String) {
        try {
            val file = File(directory, "blocking.log")
            val previous = if (file.exists()) file.readLines().takeLast(499) else emptyList()
            write("blocking.log", (previous + entry).joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {
            // Logging must never prevent enforcement. No external logging or crash payloads.
        }
    }

    companion object {
        // Process-wide ordering, including across service restarts. Only this executor writes logs.
        private val logWriter = Executors.newSingleThreadExecutor { task -> Thread(task, "cadence-log") }
    }
}
data class CachedTimetable(val timetable: Timetable?)
