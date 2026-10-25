package dev.tvlauncher.firetv

/**
 * Home presses as the system log reports them. Fire OS logs the HOME intent as it acts on
 * the press, a few hundred ms before Amazon's home draws, and it's the only sign of a press
 * made while Amazon's home is already in front (no window changes then).
 */
object HomeLog {
    /** Who started HOME ([uid] 0 or 1000 is the system, i.e. the Home button) and when. */
    data class Start(val uid: Int, val atMs: Long)

    /** Only ActivityTaskManager lines from the system buffer, newest first, epoch times. */
    val LOGCAT = listOf("logcat", "-b", "system", "-v", "epoch", "-T", "1", "ActivityTaskManager:I", "ActivityManager:I", "*:S")

    fun parse(line: String): Start? {
        if (!line.contains("START u0 {") || !line.contains("cat=[android.intent.category.HOME]")) return null
        val from = line.lastIndexOf(" from uid ")
        if (from < 0) return null
        val uid = line.substring(from + 10).takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        val at = line.trimStart().substringBefore(' ').toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
        return Start(uid, at)
    }
}
