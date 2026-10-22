package app.locate.core.time

/** Wall-clock time in epoch milliseconds. Injected so time-dependent logic is testable. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
