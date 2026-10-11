package staff.android.design.core

/** Fixed INR fixture display; integer arithmetic preserves every accepted minor unit. */
fun formatInrMinor(feeMinor: Long): String {
    require(feeMinor in 0..1_000_000)
    val rupees = feeMinor / 100
    val paise = feeMinor % 100
    return if (paise == 0L) "₹$rupees" else "₹$rupees.${paise.toString().padStart(2, '0')}"
}
