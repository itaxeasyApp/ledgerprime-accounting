package com.example.accounting.domain.rendering

/**
 * The 5 built-in, "professional and visually different" invoice templates (structure + a
 * matching default color/font pairing) - pure data, no Android import, so both the picker UI and
 * [com.example.accounting.data.rendering.PdfDocumentRenderer] read the exact same source of truth,
 * and this file's own tests can assert the 5 presets are genuinely distinct without touching a
 * Canvas. A company may still edit [TemplateColors]/[TemplateTypography] on top of any preset via
 * the existing `AccountingRepository.updateDocumentTemplate` - only [seedConfigFor] establishes the
 * starting point.
 */
object InvoiceTemplatePresets {

    /** All 5 styles, in the order offered to a user (Classic first - closest to the pre-existing
     * default look, so a company that never picks a template still gets a sane starting point via
     * [com.example.accounting.domain.rendering.DocumentTemplate.builtinDefault]). */
    val ALL_STYLES: List<InvoiceTemplateStyle> = InvoiceTemplateStyle.entries.toList()

    private val classicColumns = listOf(
        "description", "hsnSacCode", "quantity", "rate", "discount", "taxableAmount", "gstRate", "lineTotal"
    )

    fun seedConfigFor(style: InvoiceTemplateStyle): TemplateVisualConfig = when (style) {
        InvoiceTemplateStyle.CLASSIC -> TemplateVisualConfig(
            layout = TemplateLayout(logoPosition = LogoPosition.TOP_LEFT, visibleColumns = classicColumns),
            typography = TemplateTypography(fontFamily = "sans-serif", baseFontSizeSp = 11, headingFontSizeSp = 16, boldHeadings = true),
            colors = TemplateColors(primary = "#1A73E8", secondary = "#5F6368", text = "#202124", border = "#DADCE0", heading = "#202124", accent = "#1A73E8"),
            style = InvoiceTemplateStyle.CLASSIC
        )
        InvoiceTemplateStyle.MODERN_BANNER -> TemplateVisualConfig(
            layout = TemplateLayout(logoPosition = LogoPosition.TOP_RIGHT, visibleColumns = classicColumns),
            typography = TemplateTypography(fontFamily = "sans-serif-medium", baseFontSizeSp = 11, headingFontSizeSp = 18, boldHeadings = true),
            colors = TemplateColors(primary = "#0F4C81", secondary = "#3D5A73", text = "#1A1A1A", border = "#0F4C81", heading = "#FFFFFF", accent = "#F2A900"),
            style = InvoiceTemplateStyle.MODERN_BANNER
        )
        InvoiceTemplateStyle.MINIMAL -> TemplateVisualConfig(
            layout = TemplateLayout(logoPosition = LogoPosition.TOP_LEFT, showBankDetails = true, visibleColumns = classicColumns),
            typography = TemplateTypography(fontFamily = "sans-serif-light", baseFontSizeSp = 10, headingFontSizeSp = 14, boldHeadings = false),
            colors = TemplateColors(primary = "#111111", secondary = "#767676", text = "#111111", border = "#E0E0E0", heading = "#111111", accent = "#767676"),
            style = InvoiceTemplateStyle.MINIMAL
        )
        InvoiceTemplateStyle.BOXED_GRID -> TemplateVisualConfig(
            layout = TemplateLayout(logoPosition = LogoPosition.TOP_LEFT, visibleColumns = classicColumns),
            typography = TemplateTypography(fontFamily = "monospace", baseFontSizeSp = 10, headingFontSizeSp = 15, boldHeadings = true),
            colors = TemplateColors(primary = "#000000", secondary = "#000000", text = "#000000", border = "#000000", heading = "#000000", accent = "#000000"),
            style = InvoiceTemplateStyle.BOXED_GRID
        )
        InvoiceTemplateStyle.ELEGANT_SERIF -> TemplateVisualConfig(
            layout = TemplateLayout(logoPosition = LogoPosition.TOP_CENTER, visibleColumns = classicColumns),
            typography = TemplateTypography(fontFamily = "serif", baseFontSizeSp = 11, headingFontSizeSp = 17, boldHeadings = true),
            colors = TemplateColors(primary = "#7A1F2B", secondary = "#8C7A5B", text = "#2B2B2B", border = "#C9B48C", heading = "#7A1F2B", accent = "#C9B48C"),
            style = InvoiceTemplateStyle.ELEGANT_SERIF
        )
    }
}
