package com.example.accounting.domain.rendering

import com.example.accounting.domain.document.DocumentType

/**
 * A [DocumentTemplate] version's lifecycle state (Phase 7D). Exactly one version per
 * `templateId` is `ACTIVE` at any time - the "current" version new renders use by default;
 * every prior version becomes `ARCHIVED` (never deleted, never mutated) so a document rendered
 * under an old version stays reproducible. `ARCHIVED` also covers a whole template lineage being
 * retired (all its versions non-selectable going forward) - the distinction between "superseded
 * by a newer version" and "retired entirely" is a UI-selection concern (Phase 7J), not a
 * structural one; both are represented identically here.
 */
enum class TemplateStatus { ACTIVE, ARCHIVED }

enum class LogoPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT }

/**
 * The 5 built-in invoice PDF layouts (structural, not just color) - see
 * [com.example.accounting.domain.rendering.InvoiceTemplatePresets] for each style's actual preset
 * [TemplateVisualConfig], and [com.example.accounting.data.rendering.PdfDocumentRenderer] for the
 * per-style drawing routine. A company's own custom [TemplateColors]/[TemplateTypography] edits
 * still apply on top of whichever style is chosen - style decides structure (header banner vs
 * plain, grid table vs ruled lines, logo placement), not colors/fonts.
 */
enum class InvoiceTemplateStyle(val displayName: String) {
    CLASSIC("Classic"),
    MODERN_BANNER("Modern Banner"),
    MINIMAL("Minimal"),
    BOXED_GRID("Boxed Grid"),
    ELEGANT_SERIF("Elegant Serif")
}

/**
 * Purely presentational - must never be read by any accounting/GST/inventory calculation.
 * Hex strings (`"#RRGGBB"`), validated only for non-blankness by the repository layer.
 */
data class TemplateColors(
    val primary: String = "#1A73E8",
    val secondary: String = "#5F6368",
    val text: String = "#202124",
    val border: String = "#DADCE0",
    val heading: String = "#202124",
    val accent: String = "#34A853"
)

data class TemplateTypography(
    val fontFamily: String = "sans-serif",
    val baseFontSizeSp: Int = 11,
    val headingFontSizeSp: Int = 16,
    val boldHeadings: Boolean = true
)

/**
 * Layout/visibility extension points (Phase 7D, Section 11) - deliberately not a drag-and-drop
 * template designer, just the toggles/config a preset layout needs. [visibleColumns] names line
 * columns from the fixed set already present on [DocumentLineData] (`description`, `hsnSacCode`,
 * `quantity`, `unit`, `rate`, `discount`, `taxableAmount`, `gstRate`, `cgst`, `sgst`, `igst`,
 * `cess`, `lineTotal`) - hiding a column never removes the underlying value from [DocumentData],
 * it only affects what a renderer draws.
 */
data class TemplateLayout(
    val showLogo: Boolean = true,
    val logoPosition: LogoPosition = LogoPosition.TOP_LEFT,
    val showHeader: Boolean = true,
    val showFooter: Boolean = true,
    val showBankDetails: Boolean = true,
    val showSignature: Boolean = true,
    val showTermsAndConditions: Boolean = true,
    val visibleColumns: List<String> = listOf(
        "description", "hsnSacCode", "quantity", "rate", "taxableAmount", "gstRate", "lineTotal"
    ),
    val marginPointsTop: Int = 36,
    val marginPointsBottom: Int = 36,
    val marginPointsLeft: Int = 32,
    val marginPointsRight: Int = 32
)

/** The whole visual configuration of one template version, persisted as a single JSON blob
 * (`DocumentTemplateEntity.configJson`, via [TemplateConfigSerializer]) - never queried at the
 * SQL level, always read/written as a whole. */
data class TemplateVisualConfig(
    val layout: TemplateLayout = TemplateLayout(),
    val typography: TemplateTypography = TemplateTypography(),
    val colors: TemplateColors = TemplateColors(),
    /** Defaults to CLASSIC - byte-identical rendering to every template saved before this field
     * existed (Moshi's Kotlin adapter fills the default for a JSON blob missing this key). */
    val style: InvoiceTemplateStyle = InvoiceTemplateStyle.CLASSIC
)

/**
 * A company-scoped, versioned document template (Phase 7D). `templateId` identifies a template
 * *lineage* (e.g. "Modern Blue Invoice"); each edit creates a new row with the same `templateId`
 * and `version + 1`, archiving the previous version rather than mutating it - see
 * [docs/42_DOCUMENT_TEMPLATE_ARCHITECTURE.md] for the full versioning/immutability rationale.
 * [isDefault] marks the one template (per companyId + documentType) a render uses when the caller
 * doesn't pick one explicitly; at most one template per companyId + documentType is default.
 */
data class DocumentTemplate(
    val templateId: String,
    val companyId: String,
    val documentType: DocumentType,
    val templateName: String,
    val version: Int,
    val status: TemplateStatus = TemplateStatus.ACTIVE,
    val isDefault: Boolean = false,
    val visualConfig: TemplateVisualConfig = TemplateVisualConfig(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val BUILTIN_DEFAULT_TEMPLATE_ID = "BUILTIN_DEFAULT"

        /** Guarantees every document is always renderable even before a company has created its
         * own template - never null, never a crash. Version `0` so it never collides with a real
         * template's version numbering (which starts at `1`). */
        fun builtinDefault(companyId: String, documentType: DocumentType): DocumentTemplate = DocumentTemplate(
            templateId = BUILTIN_DEFAULT_TEMPLATE_ID,
            companyId = companyId,
            documentType = documentType,
            templateName = "Default",
            version = 0,
            status = TemplateStatus.ACTIVE,
            isDefault = true
        )
    }
}
