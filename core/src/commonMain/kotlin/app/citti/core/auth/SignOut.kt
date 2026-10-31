package app.citti.core.auth

import app.citti.core.db.CittiDatabase

/** Clears the credentials and wipes all local data except `meta`. The DB wipe is one transaction. */
suspend fun signOut(
    store: CredentialStore,
    db: CittiDatabase,
) {
    // Credentials first: if the wipe fails the app is signed out with stale data, never the reverse.
    store.clear()
    wipeLocalData(db)
}

/** Deletes every Note, the sync state, the settings and the exclusions; keeps `meta`. */
fun wipeLocalData(db: CittiDatabase) {
    db.transaction {
        db.accountQueries.deleteAllNotes()
        db.accountQueries.deleteAllSyncState()
        db.accountQueries.deleteAllSettings()
        db.accountQueries.deleteAllFolderExclusions()
    }
}
