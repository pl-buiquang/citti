package app.citti.core.sync

/**
 * True when [category] is the Folder [prefix] itself or lies below it (`prefix/...`), never when it only
 * starts with the same letters (`a/b` does not match `a/bc`). Same rule as the `deleteByCategoryPrefix` query.
 */
fun categoryMatchesPrefix(
    category: String,
    prefix: String,
): Boolean = category == prefix || category.startsWith("$prefix/")

fun categoryMatchesAny(
    category: String,
    prefixes: Collection<String>,
): Boolean = prefixes.any { categoryMatchesPrefix(category, it) }
