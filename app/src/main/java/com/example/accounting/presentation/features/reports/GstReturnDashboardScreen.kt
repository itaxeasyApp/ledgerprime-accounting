package com.example.accounting.presentation.features.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.accounting.core.common.Money
import com.example.accounting.presentation.components.ActionButton
import com.example.accounting.presentation.components.ActionButtonStyle
import com.example.accounting.presentation.components.Amount
import com.example.accounting.presentation.components.FormField
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.components.StatusBadge
import com.example.accounting.presentation.components.TableRow
import com.example.accounting.presentation.theme.Spacing
import com.example.accounting.presentation.viewmodel.AccountingUiState
import com.example.ui.theme.IndigoContainer
import com.example.ui.theme.IndigoTax
import com.example.accounting.domain.taxation.gstreturn.GstFilingMode
import com.example.accounting.domain.taxation.gstreturn.GstQuarter
import com.example.accounting.domain.taxation.gstreturn.GstReturn
import com.example.accounting.domain.taxation.gstreturn.GstReturnApplicability
import com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity
import com.example.accounting.domain.taxation.gstreturn.GstReturnSection
import com.example.accounting.domain.taxation.gstreturn.GstReturnSectionStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnType
import com.example.accounting.domain.taxation.gstreturn.GstScheme
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The GST Return Dashboard (Rule 33) - reached from Reports Center's existing GST category, using
 * only the existing [SectionCard]/[ActionButton]/[TableRow]/[Amount] primitives (no new design
 * system). Redesigned to be compact: FY/scheme/registration render as one dense strip instead of
 * three separate cards, and a return's own status/actions collapse into fewer, denser rows so the
 * real controls sit near the top of the screen rather than below several stacked headers.
 */
@Composable
fun GstReturnDashboardView(
    uiState: AccountingUiState,
    onSelectPeriod: (GstQuarter, Int?, GstReturnType, GstReturnPeriodicity, GstFilingMode) -> Unit,
    onOpenReturn: (String) -> Unit,
    onClearSelection: () -> Unit,
    onPrepare: () -> Unit,
    onValidate: () -> Unit,
    onGenerateJson: () -> Unit,
    onShareArtifact: (String) -> Unit,
    onImportResponseFile: () -> Unit,
    onMarkFiled: (String) -> Unit,
    onSubmitOnline: () -> Unit,
    onUpdateGstEnabled: (Boolean) -> Unit,
    onUpdateGstScheme: (GstScheme) -> Unit,
    onUpdateGstFilingFrequency: (GstReturnPeriodicity) -> Unit,
    onExportCsv: () -> Unit = {},
    onExportGstrJson: () -> Unit = {},
    onSetNilReturn: (Boolean) -> Unit = {}
) {
    val selected = uiState.selectedGstReturn
    if (selected == null) {
        GstReturnPeriodPicker(uiState, onSelectPeriod, onOpenReturn, onUpdateGstEnabled, onUpdateGstScheme, onUpdateGstFilingFrequency)
    } else {
        GstReturnDetailView(
            uiState, selected, onClearSelection, onPrepare, onValidate, onGenerateJson,
            onShareArtifact, onImportResponseFile, onMarkFiled, onSubmitOnline, onExportCsv, onExportGstrJson,
            onSetNilReturn
        )
    }
}

@Composable
private fun GstReturnPeriodPicker(
    uiState: AccountingUiState,
    onSelectPeriod: (GstQuarter, Int?, GstReturnType, GstReturnPeriodicity, GstFilingMode) -> Unit,
    onOpenReturn: (String) -> Unit,
    onUpdateGstEnabled: (Boolean) -> Unit,
    onUpdateGstScheme: (GstScheme) -> Unit,
    onUpdateGstFilingFrequency: (GstReturnPeriodicity) -> Unit
) {
    val company = uiState.currentCompany
    val scheme = company?.gstScheme
    val registered = company?.gstEnabled ?: false
    var quarter by remember { mutableStateOf<GstQuarter?>(null) }
    var month by remember { mutableStateOf<Int?>(null) }
    var returnType by remember { mutableStateOf<GstReturnType?>(null) }
    var filingMode by remember { mutableStateOf(GstFilingMode.OFFLINE) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        // Play Store / user-trust requirement: this screen prepares return figures and a GSTR
        // JSON file only - it never transmits anything to the GST Network on its own
        // (UnconfiguredGstOnlineFilingGateway always reports itself as not configured, never a
        // fabricated success). Stated once, up front, rather than only after a failed tap.
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            "Not a Government Filing Service",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            "This screen prepares your GST return figures and a downloadable JSON file. It does " +
                                "not submit anything to the GST Network on your behalf - use Offline mode to " +
                                "download the JSON and file it yourself at gst.gov.in, or hand it to your tax " +
                                "professional.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        // One compact strip: FY (read from the app's own top-bar selector, never a second one
        // here) + registration/scheme/frequency toggles, replacing three separate cards. The small
        // Indigo receipt icon is this app's own dedicated GST/tax accent (`ui/theme/Color.kt`'s
        // `IndigoTax` - defined at the Phase 7J UI theme pass, deliberately distinct from Royal
        // Purple navigation, but never actually used anywhere until this pass) - gives every GST
        // screen its own consistent visual identity instead of reading as generic Royal Purple UI.
        item {
            SectionCard(
                title = uiState.currentFinancialYear?.fyCode?.let { "FY $it" } ?: "No Financial Year",
                trailing = { GstIconBadge() }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("GST Settings", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChoiceRow(listOf(true, false), registered, { if (it) "Registered" else "Unregistered" }) { onUpdateGstEnabled(it) }
                    if (registered) {
                        ChoiceRow(GstScheme.entries, scheme, { it.name }) { onUpdateGstScheme(it) }
                        if (scheme == GstScheme.REGULAR) {
                            ChoiceRow(
                                GstReturnPeriodicity.entries, company?.gstFilingFrequency,
                                { if (it == GstReturnPeriodicity.QUARTERLY) "QRMP" else "Monthly" }
                            ) { onUpdateGstFilingFrequency(it) }
                        }
                    }
                }
            }
        }

        if (registered && scheme != null) {
            item {
                SectionCard(title = "Quarter") {
                    ChoiceRow(GstQuarter.entries, quarter, { it.label }) { quarter = it; month = null }
                }
            }
        }
        if (registered && scheme != null && quarter != null) {
            val applicable = GstReturnApplicability.availableReturns(scheme, company?.gstFilingFrequency ?: GstReturnPeriodicity.MONTHLY)
            item {
                SectionCard(title = "Return") {
                    ChoiceRow(applicable.map { it.returnType }, returnType, { it.name }) { returnType = it }
                }
            }
            val periodicity = applicable.firstOrNull { it.returnType == returnType }?.periodicity
            if (periodicity == GstReturnPeriodicity.MONTHLY && quarter != null) {
                item {
                    SectionCard(title = "Month") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            quarter!!.months.chunked(3).forEach { row ->
                                ChoiceRow(row, month, { monthLabel(it) }) { month = it }
                            }
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Filing Mode") {
                    ChoiceRow(GstFilingMode.entries, filingMode, { it.name }) { filingMode = it }
                }
            }
            val monthResolved = if (periodicity == GstReturnPeriodicity.MONTHLY) month else null
            val ready = returnType != null && periodicity != null && (periodicity != GstReturnPeriodicity.MONTHLY || month != null)
            item {
                ActionButton(
                    text = "Open Return",
                    enabled = ready,
                    onClick = { onSelectPeriod(quarter!!, monthResolved, returnType!!, periodicity!!, filingMode) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (uiState.gstReturns.isNotEmpty()) {
            item { Text("Saved Returns", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)) }
            items(uiState.gstReturns, key = { it.gstReturnId }) { gr ->
                SectionCard(
                    onClick = { onOpenReturn(gr.gstReturnId) },
                    title = "${gr.returnType} - ${gr.periodKey}",
                    subtitle = "${gr.scheme} - ${gr.filingMode} - ${gr.status}"
                ) {}
            }
        }
    }
}

/** This screen's own GST/tax identity accent - `IndigoTax`/`IndigoContainer`
 * (`ui/theme/Color.kt`, Phase 7J UI) were defined specifically so a GST badge never reads as a
 * second "primary" color next to Royal Purple navigation, but were never actually wired into a
 * screen until this pass. One small badge, reused everywhere this dashboard needs a GST mark. */
@Composable
private fun GstIconBadge() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(IndigoContainer, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Receipt, contentDescription = "GST", tint = IndigoTax, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { option ->
            ActionButton(
                text = label(option),
                style = if (option == selected) ActionButtonStyle.PRIMARY else ActionButtonStyle.SECONDARY,
                onClick = { onSelect(option) }
            )
        }
    }
}

private fun monthLabel(month: Int): String = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)[month - 1]

private val sectionMapMoshi = Moshi.Builder().build()
private val sectionMapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
private val sectionMapAdapter = sectionMapMoshi.adapter<Map<String, Any?>>(sectionMapType)

/** Reads back a section's `resultDataJson` tree - never a second calculation, purely a display
 * parse of already-computed numbers. Phase 8A, Part 1 changed GSTR-1's own section trees from one
 * flat `{count, taxableValuePaise, cgstPaise, sgstPaise, igstPaise, cessPaise}` bucket per section
 * ([com.example.accounting.data.repository.AccountingRepository.bucketTotals], still what GSTR-3B/
 * GSTR-4 use) to a real, nested statutory-table shape per section (parties/invoices/notes/rows, each
 * carrying its own rate-line breakdown - see `Gstr1JsonMapping.kt`) - [sectionTotals] below reads
 * either shape uniformly by summing whatever `*Paise` leaf values it actually finds, rather than
 * assuming one fixed flat structure. */
private fun parseSectionTotals(json: String?): Map<String, Any?>? =
    json?.let { runCatching { sectionMapAdapter.fromJson(it) }.getOrNull() }

private fun Map<String, Any?>.paiseOf(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L

/** Recursively sums every occurrence of [key] found anywhere in [node] (a `Map`/`List` tree from
 * Moshi's generic decode) - safe against double-counting because every GSTR-1 section tree only
 * ever places a given `*Paise` key at the LEAF (rate-line) level, never redundantly repeated at a
 * parent level under the same name (an invoice's own derived `invoiceValuePaise`/`noteValuePaise`
 * use deliberately distinct key names for exactly this reason - see `Gstr1JsonMapping.kt`). */
private fun sumDeep(node: Any?, key: String): Long = when (node) {
    // A Map's own matching entry contributes directly; every entry (including that same one, if
    // it's a leaf Number) is also walked recursively - harmless, since sumDeep on a plain Number
    // falls through to the `else -> 0L` branch below rather than double-adding it.
    is Map<*, *> -> ((node[key] as? Number)?.toLong() ?: 0L) + node.values.sumOf { sumDeep(it, key) }
    is List<*> -> node.sumOf { sumDeep(it, key) }
    else -> 0L
}

/** A section's row/line count for display - most GSTR-1 sections already carry a top-level
 * "count" (see `buildGstReturnSections`'s GSTR1 branch); the few that don't (NIL, DOC_ISSUED) fall
 * back to their own "rows" list size. GSTR-3B/GSTR-4's flat bucket shape always has "count". */
private fun Map<String, Any?>.rowCount(): Int = when (val c = this["count"]) {
    is Number -> c.toInt()
    else -> (this["rows"] as? List<*>)?.size ?: 0
}

/**
 * One GSTR section's small widget card (Rule 33 follow-up, Phase 8A Part 1 re-shape) -
 * [section.sectionKey] is a real statutory GST Network table name (B2B/B2CL/B2CS/CDNR/CDNUR/EXP/
 * NIL/HSN/DOC_ISSUED for GSTR-1; the 3.1/Section-4 buckets for GSTR-3B - see
 * `AccountingRepository.buildGstReturnSections`'s own doc comment for the exact mapping and its
 * deliberate limits), never an invented one. Deliberately small/dense - a label, a count, and the
 * taxable+tax totals in one row - not a full report page. [onClick] (Phase 8A, Part 2) opens this
 * section's own drill-in detail screen ([GstSectionDetailScreen]) - the tap target the widget
 * itself never had before this pass.
 */
@Composable
private fun GstSectionWidgetCard(section: GstReturnSection, onClick: () -> Unit) {
    val totals = parseSectionTotals(section.resultDataJson)
    val (bg, fg) = gstSectionStatusColors(section.status)
    SectionCard(
        title = sectionLabel(section.sectionKey),
        trailing = { StatusBadge(text = section.status.name.replace('_', ' '), containerColor = bg, contentColor = fg) },
        onClick = onClick
    ) {
        if (totals != null) {
            val taxable = sumDeep(totals, "taxableValuePaise")
            val tax = sumDeep(totals, "cgstPaise") + sumDeep(totals, "sgstPaise") + sumDeep(totals, "igstPaise")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${totals.rowCount()} txn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Amount(Money.fromPaise(taxable), style = MaterialTheme.typography.bodySmall)
                Amount(Money.fromPaise(tax), style = MaterialTheme.typography.bodySmall, emphasize = tax > 0)
            }
        }
    }
}

private fun sectionLabel(key: String): String = when (key) {
    "B2B" -> "B2B - Registered"
    "B2CL" -> "B2C - Large Invoices"
    "B2CS" -> "B2C - Small (Summary)"
    "CDNR" -> "Credit/Debit Notes - Registered"
    "CDNUR" -> "Credit/Debit Notes - Unregistered"
    "EXP" -> "Exports"
    "NIL" -> "Nil-Rated / Exempt"
    "HSN" -> "HSN Summary"
    "DOC_ISSUED" -> "Documents Issued"
    // Pre-Phase-8A key names, kept so an already-PREPARED return from before this pass still
    // renders its old sections with a real label instead of the raw key, until it is re-prepared.
    "B2C" -> "B2C - Unregistered"
    "NIL_EXEMPT" -> "Nil-Rated / Exempt"
    "OUTWARD_TAXABLE" -> "3.1 Outward Taxable"
    "OUTWARD_ZERO_RATED" -> "3.1 Zero-Rated"
    "OUTWARD_NIL_EXEMPT" -> "3.1 Nil / Exempt"
    "RCM_LIABILITY" -> "3.1(d) RCM Liability"
    "ITC_FORWARD" -> "4A ITC (Forward Charge)"
    "ITC_RCM" -> "4A ITC (Reverse Charge)"
    else -> key
}

/** Maps [GstReturnStatus]/[GstReturnSectionStatus] onto this app's own existing semantic accents
 * (Emerald=success, Crimson=error/failed, Amber=in-progress - the same three "status accents"
 * every other screen already uses, per `ui/theme/Color.kt`'s own doc comment: "unchanged by the
 * Royal Purple swap"). Read through `MaterialTheme.colorScheme.*` (never the raw Color constants)
 * so both light and dark theme stay correct automatically. */
@Composable
private fun gstReturnStatusColors(status: GstReturnStatus): Pair<Color, Color> = when (status) {
    GstReturnStatus.DRAFT ->
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    GstReturnStatus.VALIDATION_FAILED, GstReturnStatus.FAILED, GstReturnStatus.REJECTED ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    GstReturnStatus.READY, GstReturnStatus.FILED ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    GstReturnStatus.SUBMITTING, GstReturnStatus.SUBMITTED, GstReturnStatus.PROCESSING ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
}

@Composable
private fun gstSectionStatusColors(status: GstReturnSectionStatus): Pair<Color, Color> = when (status) {
    GstReturnSectionStatus.PENDING ->
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    GstReturnSectionStatus.PREPARED ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    GstReturnSectionStatus.VALIDATION_PASSED ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    GstReturnSectionStatus.VALIDATION_FAILED ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
}

/** Phase 8A, Part 2 - the GST Return Detail screen's in-place sub-views (Section Details/
 * Validation/Summary all drill in from here and back out to the same Main view - no new nav
 * route, matching this screen's existing local-`remember`-state pattern used everywhere else in
 * `ReportsCenterScreen.kt`, never a second navigation mechanism). */
private sealed class GstDetailSubView {
    object Main : GstDetailSubView()
    data class SectionDetail(val sectionKey: String) : GstDetailSubView()
    object Validation : GstDetailSubView()
    object Summary : GstDetailSubView()
}

/**
 * Declaration strip (Phase 8A, Part 2) - every real GST return form ends with a Name/GSTIN/Place/
 * Date declaration block; this reads it straight off data the company already has on file
 * ([AccountingUiState.individualProfile]/[AccountingUiState.businessProfile]/
 * [AccountingUiState.currentCompany]) rather than asking the user to retype it here. Never
 * editable from this screen - it is a read-only reflection of the Business/Individual Profile
 * (edit those from Settings if any of this is wrong), and Date is always today's real device date
 * (a declaration date, not a stored fact). Falls back sensibly (Individual name -> Business legal
 * name -> Business trading name -> Company name) rather than showing a blank Name for a company
 * that only ever filled in part of its profile.
 */
@Composable
private fun GstDeclarationCard(uiState: AccountingUiState) {
    val company = uiState.currentCompany
    val business = uiState.businessProfile
    val individual = uiState.individualProfile
    val name = individual?.name?.takeIf { it.isNotBlank() }
        ?: business?.legalName?.takeIf { it.isNotBlank() }
        ?: business?.businessName?.takeIf { it.isNotBlank() }
        ?: company?.name ?: "Not set - complete your Business Profile"
    val gstin = company?.gstin?.takeIf { it.isNotBlank() } ?: "Not set"
    val place = business?.city?.takeIf { it.isNotBlank() }
        ?: company?.stateName?.takeIf { it.isNotBlank() } ?: "Not set"
    val today = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }

    SectionCard(title = "Declaration") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TableRow("Name", value = name)
            TableRow("GSTIN", value = gstin)
            TableRow("Place", value = place)
            TableRow("Date", value = today)
        }
    }
}

@Composable
private fun GstReturnDetailView(
    uiState: AccountingUiState,
    gstReturn: GstReturn,
    onClearSelection: () -> Unit,
    onPrepare: () -> Unit,
    onValidate: () -> Unit,
    onGenerateJson: () -> Unit,
    onShareArtifact: (String) -> Unit,
    onImportResponseFile: () -> Unit,
    onMarkFiled: (String) -> Unit,
    onSubmitOnline: () -> Unit,
    onExportCsv: () -> Unit,
    onExportGstrJson: () -> Unit,
    onSetNilReturn: (Boolean) -> Unit
) {
    var ackNumber by remember(gstReturn.gstReturnId) { mutableStateOf("") }
    var subView by remember(gstReturn.gstReturnId) { mutableStateOf<GstDetailSubView>(GstDetailSubView.Main) }
    val canPrepare = gstReturn.status in setOf(GstReturnStatus.DRAFT, GstReturnStatus.VALIDATION_FAILED)

    when (val sv = subView) {
        is GstDetailSubView.SectionDetail -> {
            val section = uiState.selectedGstReturnSections.firstOrNull { it.sectionKey == sv.sectionKey }
            GstSectionDetailScreen(section, sectionLabel(sv.sectionKey), onBack = { subView = GstDetailSubView.Main })
            return
        }
        GstDetailSubView.Validation -> {
            GstValidationScreen(gstReturn, uiState.selectedGstReturnSections, onBack = { subView = GstDetailSubView.Main })
            return
        }
        GstDetailSubView.Summary -> {
            GstReturnSummaryScreen(uiState.selectedGstReturnSections, onBack = { subView = GstDetailSubView.Main })
            return
        }
        GstDetailSubView.Main -> Unit
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        // Status + Prepare/Validate collapsed into one compact card (was two stacked cards with
        // their own headings) - the two actions sit in a single row.
        item {
            val (statusBg, statusFg) = gstReturnStatusColors(gstReturn.status)
            SectionCard(
                title = "${gstReturn.returnType} - ${gstReturn.periodKey}",
                subtitle = "${gstReturn.scheme} - ${gstReturn.filingMode}",
                trailing = { GstIconBadge() }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Status", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StatusBadge(text = gstReturn.status.name.replace('_', ' '), containerColor = statusBg, contentColor = statusFg)
                    }
                    if (gstReturn.errorMessage != null) TableRow("Message", value = gstReturn.errorMessage)
                    if (gstReturn.acknowledgementNumber != null) TableRow("Acknowledgement No.", value = gstReturn.acknowledgementNumber)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ActionButton(text = "Prepare", onClick = onPrepare, enabled = canPrepare, modifier = Modifier.weight(1f))
                        ActionButton(text = "Validate", style = ActionButtonStyle.SECONDARY, onClick = onValidate, enabled = canPrepare, modifier = Modifier.weight(1f))
                    }
                    // Nil Return declaration (Phase 8A, Part 2) - a real GST concept: the taxpayer
                    // explicitly confirms zero outward supplies for the period, rather than a
                    // return silently reading as "empty" with no acknowledgement either way. See
                    // Gstr1Validator's ZERO_OUTWARD_SUPPLIES_NOT_DECLARED_NIL warning, which this
                    // toggle is the only thing that suppresses. Editable any time before FILED.
                    if (gstReturn.status != GstReturnStatus.FILED) {
                        HorizontalDivider()
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("This is a Nil Return", style = MaterialTheme.typography.bodyMedium)
                                Text("No outward supplies for this period", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = gstReturn.isNilReturn, onCheckedChange = onSetNilReturn)
                        }
                    }
                }
            }
        }

        item { GstDeclarationCard(uiState) }

        if (uiState.selectedGstReturnSections.isNotEmpty()) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Sections", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(text = "Summary", style = ActionButtonStyle.TEXT, onClick = { subView = GstDetailSubView.Summary })
                        ActionButton(text = "Validation", style = ActionButtonStyle.TEXT, onClick = { subView = GstDetailSubView.Validation })
                    }
                }
            }
            items(uiState.selectedGstReturnSections, key = { it.sectionId }) { section ->
                GstSectionWidgetCard(section, onClick = { subView = GstDetailSubView.SectionDetail(section.sectionKey) })
            }
        }

        if (gstReturn.filingMode == GstFilingMode.OFFLINE) {
            item {
                SectionCard(title = "Offline Filing") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            ActionButton(
                                text = "Generate JSON", onClick = onGenerateJson, modifier = Modifier.weight(1f),
                                enabled = gstReturn.status == GstReturnStatus.READY
                            )
                            val artifact = uiState.selectedGstReturnArtifacts.firstOrNull { it.artifactId == gstReturn.latestRequestArtifactId }
                            ActionButton(
                                text = "Share", style = ActionButtonStyle.SECONDARY,
                                onClick = { artifact?.let { onShareArtifact(it.jsonContent) } },
                                modifier = Modifier.weight(1f), enabled = artifact != null
                            )
                        }
                        // Export - JSON/CSV/GST JSON (Phase 8A, Part 2). JSON/CSV use this project's
                        // own readable shape (never fabricated statutory-looking figures for a
                        // return that isn't READY); GST JSON is the real GSTN field-name schema
                        // (Gstr1PortalJsonSerializer) for offline upload at gst.gov.in. All three
                        // read the SAME already-computed Gstr1ReturnData - no recalculation here.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            ActionButton(
                                text = "Export CSV", style = ActionButtonStyle.SECONDARY, onClick = onExportCsv,
                                modifier = Modifier.weight(1f), enabled = gstReturn.status == GstReturnStatus.READY
                            )
                            ActionButton(
                                text = "Export GST JSON", style = ActionButtonStyle.SECONDARY, onClick = onExportGstrJson,
                                modifier = Modifier.weight(1f), enabled = gstReturn.status == GstReturnStatus.READY
                            )
                        }
                        ActionButton(
                            text = "Import GST Response JSON", style = ActionButtonStyle.SECONDARY,
                            onClick = onImportResponseFile, modifier = Modifier.fillMaxWidth(),
                            enabled = gstReturn.status in setOf(GstReturnStatus.READY, GstReturnStatus.PROCESSING)
                        )
                        if (gstReturn.status == GstReturnStatus.PROCESSING) {
                            FormField(value = ackNumber, onValueChange = { ackNumber = it }, label = "Acknowledgement Number", modifier = Modifier.fillMaxWidth())
                            ActionButton(text = "Mark as Filed", onClick = { onMarkFiled(ackNumber) }, modifier = Modifier.fillMaxWidth(), enabled = ackNumber.isNotBlank())
                        }
                    }
                }
            }
        } else {
            item {
                SectionCard(title = "Online Filing", subtitle = "No GST Network integration configured") {
                    ActionButton(text = "Submit Online", onClick = onSubmitOnline, modifier = Modifier.fillMaxWidth(), enabled = gstReturn.status == GstReturnStatus.READY)
                }
            }
        }

        if (uiState.selectedGstReturnArtifacts.isNotEmpty()) {
            item { Text("Artifact History", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)) }
            items(uiState.selectedGstReturnArtifacts, key = { it.artifactId }) { artifact ->
                SectionCard(title = artifact.artifactType.name, subtitle = "Schema ${artifact.schemaVersion}") {}
            }
        }

        item {
            ActionButton(text = "Back to Period Selection", style = ActionButtonStyle.TEXT, onClick = onClearSelection, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Phase 8A, Part 2's own compact back header - [ReportsCenterScreen]'s `BackRow` is file-private
 * there, so this mirrors its exact look (icon + ellipsized title) rather than duplicating a public
 * copy of it for one extra file. */
@Composable
private fun GstDetailBackHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(
            title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// ==================== Section Details drill-in ====================

private fun Map<*, *>.toStringKeyMap(): Map<String, Any?> = entries.associate { (it.key as? String ?: it.key.toString()) to it.value }

/** Extracts this section's natural "one row per real-world record" list (Phase 8A, Part 2) - the
 * exact leaf shape [Gstr1ReturnBuilder]'s own tables use (one invoice, one note, one HSN/rate
 * summary row, one document-series row) - reusing [Gstr1ReturnBuilder]/[Gstr1Validator]'s own
 * already-computed JSON, never a second parse-and-recalculate. */
private fun flattenSectionRows(sectionKey: String, tree: Map<String, Any?>): List<Map<String, Any?>> {
    fun asList(key: String): List<*> = tree[key] as? List<*> ?: emptyList<Any?>()
    return when (sectionKey) {
        "B2B" -> asList("parties").flatMap { p ->
            val party = (p as? Map<*, *>)?.toStringKeyMap() ?: return@flatMap emptyList()
            val gstin = party["recipientGstin"] as? String ?: ""
            (party["invoices"] as? List<*> ?: emptyList<Any?>()).mapNotNull { inv ->
                (inv as? Map<*, *>)?.toStringKeyMap()?.plus("recipientGstin" to gstin)
            }
        }
        "CDNR" -> asList("parties").flatMap { p ->
            val party = (p as? Map<*, *>)?.toStringKeyMap() ?: return@flatMap emptyList()
            val gstin = party["recipientGstin"] as? String ?: ""
            (party["notes"] as? List<*> ?: emptyList<Any?>()).mapNotNull { note ->
                (note as? Map<*, *>)?.toStringKeyMap()?.plus("recipientGstin" to gstin)
            }
        }
        "B2CL", "EXP" -> asList("invoices").mapNotNull { (it as? Map<*, *>)?.toStringKeyMap() }
        "CDNUR" -> asList("notes").mapNotNull { (it as? Map<*, *>)?.toStringKeyMap() }
        "B2CS", "NIL", "HSN", "DOC_ISSUED" -> asList("rows").mapNotNull { (it as? Map<*, *>)?.toStringKeyMap() }
        else -> emptyList()
    }
}

private val ROW_PAISE_FIELDS = setOf("taxableValuePaise", "invoiceValuePaise", "noteValuePaise", "totalValuePaise", "cgstPaise", "sgstPaise", "igstPaise", "cessPaise")
private val ROW_FIELD_LABELS = mapOf(
    "recipientGstin" to "GSTIN", "invoiceNumber" to "Invoice No.", "noteNumber" to "Note No.",
    "invoiceDate" to "Date", "noteDate" to "Date", "posStateCode" to "Place of Supply",
    "hsnSacCode" to "HSN/SAC", "gstRatePercent" to "Rate %", "reverseCharge" to "Reverse Charge",
    "natureOfDocument" to "Document", "seriesFrom" to "From", "seriesTo" to "To",
    "totalCount" to "Total Issued", "cancelledCount" to "Cancelled", "netIssued" to "Net Issued",
    "originalInvoiceNumber" to "Original Invoice", "originalInvoiceDate" to "Original Date",
    "bucket" to "Type", "interState" to "Inter-State", "totalQuantity" to "Quantity",
    "noteType" to "Note Type", "amendsFiledPeriod" to "Amends a Filed Period",
    "taxableValuePaise" to "Taxable Value", "invoiceValuePaise" to "Invoice Value",
    "noteValuePaise" to "Note Value", "totalValuePaise" to "Total Value",
    "cgstPaise" to "CGST", "sgstPaise" to "SGST", "igstPaise" to "IGST", "cessPaise" to "Cess"
)

private fun rowDisplayFields(row: Map<String, Any?>): List<Pair<String, String>> =
    row.entries.filter { it.key != "voucherId" && it.key != "rateLines" && it.value != null }
        .mapNotNull { (key, value) ->
            val label = ROW_FIELD_LABELS[key] ?: return@mapNotNull null
            val text = if (key in ROW_PAISE_FIELDS) Money.fromPaise((value as? Number)?.toLong() ?: 0L).formatPlain() else value.toString()
            label to text
        }

@Composable
private fun GstDetailRowCard(row: Map<String, Any?>) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rowDisplayFields(row).forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

/** Section Details drill-in (Phase 8A, Part 2) - one card per real invoice/note/HSN-rate/document-
 * series row, reading the EXACT same `resultDataJson` the section widget card already parses for
 * its totals - never a second GST calculation, purely a deeper display of the same data. */
@Composable
private fun GstSectionDetailScreen(section: GstReturnSection?, title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GstDetailBackHeader(title, onBack)
        val tree = parseSectionTotals(section?.resultDataJson)
        val rows = if (section != null && tree != null) flattenSectionRows(section.sectionKey, tree) else emptyList()
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No records in this section for the prepared period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(rows.size) { index -> GstDetailRowCard(rows[index]) }
            }
        }
    }
}

// ==================== Validation screen ====================

/** One parsed validation finding - built from a section's `errorsJson` (`{"errors": [...],
 * "warnings": [...]}`, written once per return by [AccountingRepository.validateGstReturn] using
 * the SAME list for every section - see that function's own comment) - never a second validation
 * pass, purely a structured display of the messages [Gstr1Validator] already produced. */
private data class GstValidationFinding(val isError: Boolean, val message: String)

private fun parseValidationFindings(sections: List<GstReturnSection>): List<GstValidationFinding> {
    val errorsJson = sections.firstOrNull { it.errorsJson != null }?.errorsJson ?: return emptyList()
    val parsed = runCatching { sectionMapAdapter.fromJson(errorsJson) }.getOrNull() ?: return emptyList()
    val errors = (parsed["errors"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    val warnings = (parsed["warnings"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    return errors.map { GstValidationFinding(isError = true, message = it) } + warnings.map { GstValidationFinding(isError = false, message = it) }
}

@Composable
private fun GstValidationScreen(gstReturn: GstReturn, sections: List<GstReturnSection>, onBack: () -> Unit) {
    val findings = parseValidationFindings(sections)
    Column(modifier = Modifier.fillMaxWidth()) {
        GstDetailBackHeader("Validation Results", onBack)
        if (gstReturn.status !in setOf(GstReturnStatus.READY, GstReturnStatus.VALIDATION_FAILED, GstReturnStatus.SUBMITTING, GstReturnStatus.SUBMITTED, GstReturnStatus.PROCESSING, GstReturnStatus.FILED)) {
            Text("Run Validate first to see results here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            return
        }
        if (findings.isEmpty()) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                    Text(
                        "No errors or warnings - this return is clean.", modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            return
        }
        val errors = findings.filter { it.isError }
        val warnings = findings.filter { !it.isError }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            if (errors.isNotEmpty()) {
                item { Text("Errors (${errors.size}) - block filing until resolved", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error) }
                items(errors.size) { i -> GstFindingCard(errors[i]) }
            }
            if (warnings.isNotEmpty()) {
                item { Text("Warnings (${warnings.size}) - review, does not block filing", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.tertiary) }
                items(warnings.size) { i -> GstFindingCard(warnings[i]) }
            }
        }
    }
}

@Composable
private fun GstFindingCard(finding: GstValidationFinding) {
    val (bg, fg, icon) = if (finding.isError) {
        Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Icons.Default.Error)
    } else {
        Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Icons.Default.Warning)
    }
    Surface(shape = RoundedCornerShape(10.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Text(finding.message, style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// ==================== Summary screen ====================

/** Dedicated Summary screen (Phase 8A, Part 2) - one row per section: name, record count, taxable
 * value, tax. Reuses [sumDeep]/[rowCount] (the same shape-agnostic readers the section widget cards
 * already use) - never a second aggregation. */
@Composable
private fun GstReturnSummaryScreen(sections: List<GstReturnSection>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GstDetailBackHeader("Return Summary", onBack)
        if (sections.isEmpty()) {
            Text("Nothing to summarize yet - run Prepare first.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            return
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Section", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1.4f))
            Text("Count", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(0.6f))
            Text("Amount", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
        }
        var totalTaxable = 0L
        var totalTax = 0L
        val rows = sections.map { section ->
            val tree = parseSectionTotals(section.resultDataJson)
            val taxable = tree?.let { sumDeep(it, "taxableValuePaise") } ?: 0L
            val tax = tree?.let { sumDeep(it, "cgstPaise") + sumDeep(it, "sgstPaise") + sumDeep(it, "igstPaise") } ?: 0L
            totalTaxable += taxable
            totalTax += tax
            Triple(section, tree?.rowCount() ?: 0, taxable to tax)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            items(rows.size) { i ->
                val (section, count, amounts) = rows[i]
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(sectionLabel(section.sectionKey), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1.4f))
                    Text(count.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Money.fromPaise(amounts.first).formatPlain(), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        if (amounts.second > 0) Text("+${Money.fromPaise(amounts.second).formatPlain()} tax", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1.4f + 0.6f))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Money.fromPaise(totalTaxable).formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                        if (totalTax > 0) Text("+${Money.fromPaise(totalTax).formatPlain()} tax", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
