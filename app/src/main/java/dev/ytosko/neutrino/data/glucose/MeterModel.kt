package dev.ytosko.neutrino.data.glucose

/**
 * The meters Neutrino supports. They all speak the standard Bluetooth Glucose Profile, so syncing
 * is identical; the model decides the picture, name and pairing instructions.
 */
enum class MeterModel(val displayName: String) {
    ContourPlusOne("Contour Plus One"),
    ContourPlusElite("Contour Plus Elite"),
    ContourPlusBlue("Contour Plus Blue"),
    ;

    companion object {
        fun fromKey(key: String?): MeterModel = entries.firstOrNull { it.name == key } ?: ContourPlusElite

        /** Best guess from what the meter reports about itself (used for meters paired before models existed). */
        fun guess(vararg reported: String?): MeterModel {
            val text = reported.filterNotNull().joinToString(" ").lowercase()
            return when {
                "one" in text -> ContourPlusOne
                "blue" in text -> ContourPlusBlue
                else -> ContourPlusElite
            }
        }
    }
}
