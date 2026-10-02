package com.example.accounting.domain.taxation.gstreturn

/**
 * Maps a free-text unit of measure (e.g. [com.example.accounting.core.common.Quantity.unit], which
 * defaults to "Nos") onto the GST Network's fixed Unit Quantity Code list (GSTR-1 Table 12 `uqc`).
 * The codes returned are only ever members of that list: a recognised unit maps to its code, an
 * unrecognised one to "OTH" (the list's own catch-all), and a missing unit (a service line with no
 * quantity) to "NA" - never a guessed or invented code.
 */
object GstnUqc {

    /** The official GSTN UQC list. */
    val CODES: Set<String> = setOf(
        "BAG", "BAL", "BDL", "BKL", "BOU", "BOX", "BTL", "BUN", "CAN", "CBM", "CCM", "CMS", "CTN", "DOZ",
        "DRM", "GGK", "GMS", "GRS", "GYD", "KGS", "KLR", "KME", "LTR", "MLT", "MTR", "MTS", "NOS", "OTH", "PAC",
        "PCS", "PRS", "QTL", "ROL", "SET", "SQF", "SQM", "SQY", "TBS", "TGM", "THD", "TON", "TUB", "UGS",
        "UNT", "YDS"
    )

    private val byName: Map<String, String> = mapOf(
        "nos" to "NOS", "no" to "NOS", "number" to "NOS", "numbers" to "NOS",
        "pcs" to "PCS", "pc" to "PCS", "piece" to "PCS", "pieces" to "PCS",
        "kg" to "KGS", "kgs" to "KGS", "kilogram" to "KGS", "kilograms" to "KGS",
        "gm" to "GMS", "gms" to "GMS", "gram" to "GMS", "grams" to "GMS",
        "ltr" to "LTR", "l" to "LTR", "litre" to "LTR", "liter" to "LTR", "litres" to "LTR", "liters" to "LTR",
        "ml" to "MLT", "mltr" to "MLT", "millilitre" to "MLT", "milliliter" to "MLT",
        "mtr" to "MTR", "m" to "MTR", "meter" to "MTR", "metre" to "MTR", "meters" to "MTR", "metres" to "MTR",
        "cm" to "CMS", "cms" to "CMS", "centimeter" to "CMS", "centimetre" to "CMS",
        "box" to "BOX", "boxes" to "BOX",
        "pkt" to "PAC", "pack" to "PAC", "packs" to "PAC", "packet" to "PAC", "packets" to "PAC", "pac" to "PAC",
        "set" to "SET", "sets" to "SET",
        "dozen" to "DOZ", "doz" to "DOZ", "dzn" to "DOZ",
        "pair" to "PRS", "pairs" to "PRS", "prs" to "PRS",
        "bag" to "BAG", "bags" to "BAG",
        "bottle" to "BTL", "bottles" to "BTL", "btl" to "BTL",
        "carton" to "CTN", "cartons" to "CTN", "ctn" to "CTN",
        "ton" to "TON", "tons" to "TON", "tonne" to "TON", "tonnes" to "TON",
        "qtl" to "QTL", "quintal" to "QTL", "quintals" to "QTL",
        "sqm" to "SQM", "sq.m" to "SQM", "sqft" to "SQF", "sq.ft" to "SQF", "sqf" to "SQF",
        "roll" to "ROL", "rolls" to "ROL", "rol" to "ROL",
        "unit" to "UNT", "units" to "UNT", "unt" to "UNT",
        "can" to "CAN", "cans" to "CAN", "bundle" to "BDL", "bundles" to "BDL", "bdl" to "BDL",
        "tube" to "TUB", "tubes" to "TUB", "tub" to "TUB", "drum" to "DRM", "drums" to "DRM", "drm" to "DRM"
    )

    /** `null`/blank unit (no quantity on the line, i.e. a service) -> "NA". */
    fun fromUnit(unit: String?): String {
        val key = unit?.trim()?.lowercase() ?: return "NA"
        if (key.isEmpty()) return "NA"
        val upper = key.uppercase()
        if (upper in CODES && upper != "OTH") return upper
        return byName[key] ?: "OTH"
    }
}
