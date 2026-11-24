package com.meowtaversegames.cadence

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Compiled only into debug APKs. All data is synthetic and stays in the private cache. */
@Composable
fun DebugControls(runtime: BlockingRuntime, onError: (String) -> Unit) {
    fun save(text: String) {
        try {
            runtime.store.saveTimetable(TimetableJson.decode(text))
            runtime.changed()
        } catch (_: Exception) { onError("確認用の時間割を保存できませんでした") }
    }
    Button(onClick = { save(debugTimetable(Instant.now(), 0)) }) { Text("今から 15 分ブロックする時間割を入れる") }
    Text("時間割は分単位です。現在の分から15分間、毎週の同じ曜日に繰り返します。確認後は消してください。")
    Button(onClick = { save(debugTimetable(Instant.now(), 12)) }) { Text("12 分後から 15 分ブロックする（境目の確認用）") }
    Button(onClick = { save("""{"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[]}""") }) { Text("ブロックの時間割を消す") }
}

internal fun debugTimetable(now: Instant, delayMinutes: Long): String {
    val start = now.atOffset(ZoneOffset.ofHours(9)).withSecond(0).withNano(0).plusMinutes(delayMinutes)
    val end = start.plusMinutes(15)
    val format = DateTimeFormatter.ofPattern("HH:mm")
    return """{"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[{"id":"debug-focus","name":"確認用","enabled":true,"weekdays":[${start.dayOfWeek.value}],"start":"${format.format(start)}","end":"${format.format(end)}","segments":[{"kind":"block","minutes":15}]}]}"""
}
