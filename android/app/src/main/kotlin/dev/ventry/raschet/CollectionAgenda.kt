package dev.ventry.raschet

import java.time.LocalDate

/**
 * Read-only plan projection. One saved plan per current debtor, not historical
 * promise statistics. Invalid anchors never count as paid or missed.
 */
internal enum class CollectionPlanFilter(val caption: String) {
    ALL("Все планы и долги"),
    DUE("Связаться"),
    MISSED("Не выполнены в срок"),
    FUTURE("Будущие планы"),
    REVIEW("Проверить план");

    fun matches(item: CollectionWorklist.Item, today: LocalDate): Boolean {
        if (this == ALL) return true
        val plan = item.followUp ?: return false
        val progress = item.promise
        if (plan.amount > 0) {
            if (progress == null || !progress.valid) return this == REVIEW
            if (progress.fulfilled()) return false
            if (this == MISSED) return progress.missed
        } else if (this == MISSED) return false
        return when (this) {
            DUE -> !plan.date.isAfter(today)
            FUTURE -> plan.date.isAfter(today)
            else -> false
        }
    }
}

internal data class CollectionAgenda(
    val counts: Map<CollectionPlanFilter, Int>,
    val promised: Long,
    val received: Long,
    val remaining: Long
) {
    fun count(filter: CollectionPlanFilter) = counts[filter] ?: 0

    companion object {
        fun build(work: List<CollectionWorklist.Item>, today: LocalDate): CollectionAgenda {
            val counts = CollectionPlanFilter.entries.associateWith { filter -> work.count { filter.matches(it, today) } }
            var promised = 0L
            var received = 0L
            for (item in work) {
                val plan = item.followUp ?: continue
                val progress = item.promise ?: continue
                if (plan.amount <= 0 || !progress.valid) continue
                promised = Math.addExact(promised, plan.amount)
                received = Math.addExact(received, progress.paid.coerceIn(0L, plan.amount))
            }
            return CollectionAgenda(counts, promised, received, promised - received)
        }
    }
}
