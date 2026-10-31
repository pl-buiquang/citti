package app.citti.ui.note

import kotlin.test.Test
import kotlin.test.assertEquals

class NoteInfoSizeTest {
    private fun shown(bytes: Int): String {
        val (unit, tenths) = sizeTenths(bytes)
        return if (unit == SizeUnit.B) "$bytes B" else "${tenths.tenthsText()} $unit"
    }

    @Test
    fun sizesAreBytesThenKilobytesThenMegabytesWithOneDecimal() {
        assertEquals("0 B", shown(0))
        assertEquals("1023 B", shown(1023))
        assertEquals("1.0 KB", shown(1024))
        assertEquals("4.2 KB", shown(4300))
        assertEquals("1.0 MB", shown(1024 * 1024 - 1))
        assertEquals("1023.5 KB", shown(1023 * 1024 + 512))
        assertEquals("1.0 MB", shown(1024 * 1024))
        assertEquals("2.5 MB", shown(1024 * 1024 * 5 / 2))
    }
}
