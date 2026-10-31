# Sign in with Login Flow v2, not Nextcloud SSO

The app signs in with Nextcloud Login Flow v2: the user enters the server URL, approves in the system browser, and the server returns an app password that is stored encrypted (Android Keystore). Manual URL + user + app password stays as the fallback. The app sends a clear `User-Agent` (`citti (Android)`) so the server's "Grant access" screen and the device list show the right name.

This keeps the credential per device and revocable (Settings › Security on the server), works with 2FA, SAML/OIDC and WebAuthn because approval happens in the real browser, needs no other app installed, and leaves every request in our own Ktor client. That last point matters for the sync: case-insensitive `ETag` / `Last-Modified` handling, HTTP/2 and `chunkSize` / `chunkCursor` are under our control, and the same code path works on iOS later.

Accepted risk: someone can send a phishing link that approves a flow they started. Recent servers show the app name on the approval screen, which is the mitigation.

## Considered options

- **Nextcloud SSO ([Android-SingleSignOn](https://github.com/nextcloud/Android-SingleSignOn)):** one tap and no credential held by the app, but it needs the Nextcloud Files app installed and signed in, is Android-only, and routes requests through its own layer. Its `ParsedResponse` keeps headers in a case-sensitive map, the root cause of notes-android re-downloading every note on each sync ([#2531](https://github.com/nextcloud/notes-android/issues/2531)). It could be added later as an Android-only option, but it needs its own HTTP path, so it is not planned.
- **Manual credentials only:** simplest to build, but the user has to create an app password by hand and paste it into a phone.
