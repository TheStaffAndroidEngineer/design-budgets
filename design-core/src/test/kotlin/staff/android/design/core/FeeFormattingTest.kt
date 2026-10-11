package staff.android.design.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FeeFormattingTest {
    @Test fun wholeRupeesStayCompactButEveryPaiseIsPreserved() {
        val examples = mapOf(0L to "₹0", 1L to "₹0.01", 9L to "₹0.09", 10L to "₹0.10",
            99L to "₹0.99", 100L to "₹1", 9_900L to "₹99", 9_901L to "₹99.01", 1_000_000L to "₹10000")
        examples.forEach { (minor, label) -> assertEquals(label, formatInrMinor(minor)) }
    }
    @Test fun formatterRejectsValuesOutsideTheAcceptedFeeContract() {
        for (value in listOf(-1L, 1_000_001L, Long.MIN_VALUE, Long.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { formatInrMinor(value) }
    }
}
