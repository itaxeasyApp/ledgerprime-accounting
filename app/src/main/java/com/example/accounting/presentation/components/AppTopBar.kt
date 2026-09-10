package com.example.accounting.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.financialyear.FinancialYear
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Header redesign (explicit user instruction) - collapsed from two stacked rows into one: back/
 * menu, Financial Year badge, today's date, Profile. Company name/GSTIN moved to [AppDrawerContent]
 * (already showing both - see docs/57_BUSINESS_IDENTITY_DISPLAY.md) since this single-business app
 * never needs them repeated in a header that's visible on every screen. The Search affordance moved
 * out of this bar entirely into [SearchBarRow], rendered directly below it - a real, persistent
 * search box rather than an icon, using the exact same [onSearchClicked] destination as before.
 */
@Composable
fun AppTopBar(
    currentFinancialYear: FinancialYear?,
    financialYears: List<FinancialYear>,
    onFinancialYearSelected: (FinancialYear) -> Unit,
    /** Real add-a-year capability (previously missing entirely) - extends backward from the
     * earliest FY already on file, real dates, never a fabricated/fixed year. */
    onAddPreviousFinancialYear: () -> Unit = {},
    onSearchClicked: () -> Unit = {},
    onProfileClicked: () -> Unit = {},
    onMenuClicked: () -> Unit = {},
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    /** Hides [SearchBarRow] - only ever passed `false` for the Search screen itself, which already
     * has its own dedicated search input; showing this global entry point there too duplicated it. */
    showSearchBar: Boolean = true,
    modifier: Modifier = Modifier
) {
    var fyDropdownOpen by remember { mutableStateOf(false) }
    // Today's actual calendar date - a plain system fact, never a business figure that needs to
    // come from a repository/engine.
    val todayText = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy")) }

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            // Under enableEdgeToEdge() this Surface draws behind the status bar unless it claims that
            // inset itself - without this, the whole bar (and everything below it) renders shifted up
            // under the status bar icons/notch.
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Visible back affordance for any drill-down route (Ledger Statement, Search,
                // Profile, Subscription, Settings & Sync, Data Tools, ...) - previously only the
                // system back gesture/button worked here, with zero on-screen way back.
                if (canGoBack) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("top_bar_back")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    IconButton(onClick = onMenuClicked, modifier = Modifier.testTag("top_bar_menu")) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "Menu")
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // App logo (explicit follow-up: "wants to see Ledger Prime easily, not by
                // scrolling") - always visible in the header itself, never requiring a drawer
                // open or a scroll down the Dashboard to spot the brand.
                Image(
                    painter = painterResource(id = com.example.R.drawable.ic_ledgerprime_brandmark),
                    contentDescription = "Ledger Prime",
                    modifier = Modifier.size(26.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // FY Badge
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clickable { fyDropdownOpen = true }
                            .testTag("fy_selector")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = "FY",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = currentFinancialYear?.fyCode ?: "FY 2026-27",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = fyDropdownOpen,
                        onDismissRequest = { fyDropdownOpen = false }
                    ) {
                        Text(
                            text = "Financial Year (1 Apr - 31 Mar)",
                            style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        financialYears.forEach { fy ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(fy.fyCode, fontWeight = if (fy.isCurrent) FontWeight.Bold else FontWeight.Normal)
                                        if (fy.isLocked) {
                                            Icon(Icons.Default.Lock, contentDescription = "Locked", modifier = Modifier.size(16.dp), tint = Color.Red)
                                        }
                                    }
                                },
                                onClick = {
                                    onFinancialYearSelected(fy)
                                    fyDropdownOpen = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Add Previous Year", color = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                onAddPreviousFinancialYear()
                                fyDropdownOpen = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(18.dp))

                Text(
                    text = todayText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("top_bar_date")
                )

                // Profile & Business Setup entry point - hosts Import/Subscription/Settings too
                IconButton(onClick = onProfileClicked, modifier = Modifier.testTag("top_bar_profile")) {
                    Icon(imageVector = Icons.Default.AccountCircle, contentDescription = "Profile & Business Setup")
                }
            }
        }

        if (showSearchBar) {
            SearchBarRow(onClick = onSearchClicked)
        }
    }
}

/**
 * Persistent search box shown directly below [AppTopBar] (explicit user instruction: "search bar
 * not in header, just below of header") - a real, always-visible affordance rather than an icon
 * that has to be discovered/tapped first. Still just navigates to the existing Search screen via
 * [onClick]; this component performs no searching itself.
 */
@Composable
private fun SearchBarRow(onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable(onClick = onClick)
                .testTag("top_bar_search")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Search vouchers, parties, items...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
