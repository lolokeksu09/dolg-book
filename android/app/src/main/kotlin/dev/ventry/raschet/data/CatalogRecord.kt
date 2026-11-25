package dev.ventry.raschet.data

/** Immutable catalog data; the legacy storage validator remains authoritative. */
data class CatalogRecord(
    val id: String, val name: String, val category: String, val unit: String,
    val price: Long, val favorite: Boolean, val archived: Boolean
)
