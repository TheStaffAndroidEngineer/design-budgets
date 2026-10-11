package staff.android.design.planning

fun main() {
    val items = listOf(
        MediaItem("cover", true, listOf(
            MediaVariant("compact", 2_000, 4_000, 4),
            MediaVariant("detailed", 5_000, 7_000, 7),
        )),
        MediaItem("preview", false, listOf(MediaVariant("compact", 4_000, 4_000, 6))),
    )
    println("Invented media policy; byte totals are exact only inside this supplied model.")
    println(ReferencePlanner.plan(ResourceBudget(6_000, 12_000, 2_000), items))
    println(ReferencePlanner.plan(ResourceBudget(6_000, 9_000, 2_000), items))
}
