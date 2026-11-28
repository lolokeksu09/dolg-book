package dev.ventry.raschet

import dev.ventry.raschet.domain.DatedLedger
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/** Calendar dates descending, reverse insertion order within each day; no financial writes. */
object HistoryRows {
    @JvmStatic
    fun select(entries: List<LedgerStore.Entry>, people: Map<String, LedgerStore.Client>,
        clientId: String?, query: String?, from: LocalDate?, to: LocalDate?, type: String?, zone: ZoneId): List<LedgerStore.Entry> {
        val needle = ClientSearch.normalize(query)
        val days = TreeMap<LocalDate, MutableList<LedgerStore.Entry>>(reverseOrder())
        val matchingContacts = HashMap<String, Boolean>()
        for (entry in entries.asReversed()) {
            if (clientId != null && clientId != entry.clientId) continue
            if (type == "Долги" && entry.type != "debt" || type == "Оплаты" && entry.type != "payment" ||
                type == "Отмены" && entry.type != "reversal") continue
            val date = DatedLedger.localDate(entry.date, zone)
            if (from != null && date.isBefore(from) || to != null && date.isAfter(to)) continue
            if (needle.isNotEmpty()) {
                val contact = matchingContacts.getOrPut(entry.clientId) {
                    people[entry.clientId]?.let {
                        ClientSearch.normalize(it.name).contains(needle) || ClientSearch.normalize(it.phone).contains(needle) ||
                            ClientSearch.phoneMatches(it.phone, needle)
                    } ?: false
                }
                if (!contact && !ClientSearch.normalize(entry.note).contains(needle)) continue
            }
            days.getOrPut(date) { ArrayList() }.add(entry)
        }
        return days.values.flatten()
    }
}
