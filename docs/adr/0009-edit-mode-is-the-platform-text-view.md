# Edit mode is the platform text view (`EditText` on Android)

Edit mode must show syntax-highlighted markdown and stay smooth on notes of thousands of lines (the longest live note has 2,873; an old one 9,271). On the phone (SM-S931B, 120 Hz, release build), Compose's `BasicTextField(TextFieldState)` took 81 ms per keystroke on the 2,873-line note with no styling (547 ms with styling), because it lays out the whole text on every change (about 3 ms per 100 lines, so about 200 lines fit in a frame). An Android `EditText` took 4 ms there and 5–6 ms on the 9,271-line note, because its layout only re-measures the edited paragraph. So Edit mode is an `EditText` on Android, behind an expect/actual `MarkdownEditor`, styled with spans by a line lexer that only re-lexes the lines an edit touched. The lexer, the toolbar and Enter operations, the undo history, saving and search matching stay in shared code; only the view is per platform, and iOS will get a `UITextView` actual. The cost is a second editor for iOS, our own undo history (`EditText` has none), and View interop inside a Compose screen.

## Considered options

- **Compose `BasicTextField`, highlighting off above a line limit:** one editor for both platforms, but it lags on long notes even without styling, so turning highlighting off does not help.
- **A lazy Compose editor (one text field per block):** multiplatform, but selection across blocks, caret movement between fields, toolbar actions over several lines and undo over the whole note all get hard.
- **`BasicTextField` for short notes, `EditText` for long ones:** two engines anyway.
