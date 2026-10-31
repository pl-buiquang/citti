package app.citti.core.auth

import app.citti.core.db.CittiDatabase

const val KEY_ACCOUNT_SERVER = "account.server"
const val KEY_ACCOUNT_USER = "account.user"

/** The account a local database belongs to. */
data class AccountId(
    val server: String,
    val user: String,
) {
    companion object {
        fun of(c: Credentials) = AccountId(normalizeServer(c.serverUrl), c.user)
    }
}

internal fun normalizeServer(url: String): String = url.trim().trimEnd('/').lowercase()

/**
 * True when the stored database belongs to another server or user than [new] and must be wiped before
 * [new] signs in. A database with no recorded account is kept (nothing says it is foreign).
 */
fun accountChanged(
    stored: AccountId?,
    new: AccountId,
): Boolean = stored != null && stored != new

fun storedAccount(db: CittiDatabase): AccountId? {
    val server = db.settingQueries.selectSetting(KEY_ACCOUNT_SERVER).executeAsOneOrNull()
    val user = db.settingQueries.selectSetting(KEY_ACCOUNT_USER).executeAsOneOrNull()
    return if (server != null && user != null) AccountId(server, user) else null
}

/**
 * Call before saving new credentials: wipes the local data when it belongs to another account, then
 * records [credentials] as the owner. Returns true when a wipe happened. The credential store is untouched.
 */
fun prepareForSignIn(
    db: CittiDatabase,
    credentials: Credentials,
): Boolean {
    val id = AccountId.of(credentials)
    val wipe = accountChanged(storedAccount(db), id)
    if (wipe) wipeLocalData(db)
    db.settingQueries.upsertSetting(KEY_ACCOUNT_SERVER, id.server)
    db.settingQueries.upsertSetting(KEY_ACCOUNT_USER, id.user)
    return wipe
}
