package dev.remotecode.data

/** 完了前の読み取りと導入後の読み取りを分け、古い応答の後書きを防ぐ。 */
internal class MetadataRefresh {
    private var generation = 0L

    @Synchronized
    fun ticket(fresh: Boolean = false): Long {
        if (fresh) generation++
        return generation
    }

    @Synchronized
    fun publish(ticket: Long, apply: () -> Unit): Boolean {
        if (ticket != generation) return false
        apply()
        return true
    }
}
