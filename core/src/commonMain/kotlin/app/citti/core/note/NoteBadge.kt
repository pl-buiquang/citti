package app.citti.core.note

/** The state badge of a row in List, Tree and Search. */
enum class NoteBadge { Pending, Conflict }

/** A Conflict copy shows Conflict; any other Note with changes not yet pushed shows Pending. */
fun noteBadgeOf(
    conflictOf: Long?,
    syncState: String,
): NoteBadge? =
    when {
        conflictOf != null -> NoteBadge.Conflict
        syncState != "SYNCED" -> NoteBadge.Pending
        else -> null
    }
