package dev.ventry.raschet

/** Read-only suggestions from actual active debt entries, newest insertion first. */
object RecentDebts {
    @JvmStatic
    fun lastAmounts(entries: List<LedgerStore.Entry>): Map<String, Long> {
        val canceled = LedgerStore.canceled(entries)
        val result = LinkedHashMap<String, Long>()
        for (entry in entries.asReversed()) {
            if (entry.type == "debt" && entry.id !in canceled) result.putIfAbsent(entry.clientId, entry.amount)
        }
        return result
    }
}
