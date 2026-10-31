# Build greenfield with Kotlin Multiplatform, not a fork of notes-android

We build a new app in Kotlin Multiplatform + Compose Multiplatform (Ktor, SQLDelight, Glance widgets) and use [notes-android](https://github.com/nextcloud/notes-android) only as a reference. A fork would be Android-only Java/XML Views/RxJava2, tied to Nextcloud SSO and GPL-3.0, and would still need tree view, folder exclusion, manual login, chunked sync and image handling. Shared Kotlin gives one sync/DB/markdown core for Android and a later iOS target, and widgets read the same SQLDelight database.

## Considered options

- **Fork notes-android:** quick wins (header-case sync bug [#2531](https://github.com/nextcloud/notes-android/issues/2531), widget tap bug) but most of the spec is still new work on a legacy base.
- **Flutter:** good UI, but widgets need native Kotlin/Swift plus a bridge, with data shared through files or prefs.
- **Expo / React Native:** familiar TypeScript, but widgets are native via config plugins and the editor is weakest on long notes.
