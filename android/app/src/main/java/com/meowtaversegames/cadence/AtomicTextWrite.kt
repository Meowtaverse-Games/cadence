package com.meowtaversegames.cadence

import java.io.OutputStream

/** Android supplies AtomicFile's start/finish/fail operations; rollback also covers partial writes. */
fun <T : OutputStream> atomicTextWrite(text: String, start: () -> T, finish: (T) -> Unit, rollback: (T) -> Unit) {
    val stream = start()
    try {
        stream.write(text.toByteArray(Charsets.UTF_8))
        finish(stream)
    } catch (failure: Exception) {
        rollback(stream)
        throw failure
    }
}
