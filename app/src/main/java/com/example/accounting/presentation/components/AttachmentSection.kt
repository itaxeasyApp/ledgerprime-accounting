package com.example.accounting.presentation.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.accounting.data.local.dao.VoucherAttachmentRow
import com.example.accounting.data.rendering.ShareAdapter
import com.example.accounting.presentation.theme.Radius
import com.example.accounting.presentation.theme.Spacing
import java.io.File

/**
 * Phase 7J-B.2 (Slice 2, Step 4) - the one reusable attachment list+actions block, shared verbatim
 * between [com.example.accounting.presentation.components.VoucherDetailDialog] and (once a real
 * voucherId exists to attach against) a future draft-review wiring - never a per-screen copy. Every
 * value shown ([attachments]) comes straight from [com.example.accounting.presentation.viewmodel.AccountingViewModel.loadVoucherAttachments]'s
 * real Room-backed query - nothing here is static/seeded (Slice 2, Step 12). This component never
 * writes Room, never validates company/duplicate rules, never deletes a physical file directly, and
 * never touches Outbox/server - all of that stays in the ViewModel/service layer one level below;
 * View/Share/"Open externally" are the sole exceptions, and only because they are pure, non-
 * mutating platform actions (a file-existence check + an [Intent]), not domain operations.
 */
@Composable
fun AttachmentSection(
    attachments: List<VoucherAttachmentRow>,
    isLoading: Boolean,
    isAttaching: Boolean,
    removingReferenceId: String?,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (VoucherAttachmentRow) -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(modifier = modifier, elevated = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Attachments", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            ActionButton(text = "+ Attach", style = ActionButtonStyle.TEXT, onClick = onAttachClick, enabled = !isAttaching)
        }
        Spacer(modifier = Modifier.height(Spacing.sm))

        when {
            isLoading -> Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) { AppLoader() }
            isAttaching -> Row(verticalAlignment = Alignment.CenterVertically) {
                AppLoader(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text("Attaching...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            attachments.isEmpty() -> Text(
                text = "No attachments yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.sm)
            )
            // Bounded + internally scrollable (Slice 2, Step 5) - a long attachment list scrolls
            // within this section instead of pushing a host dialog's fixed footer buttons off
            // screen (the hosts, e.g. VoucherDetailDialog, deliberately do not wrap their whole
            // Column in verticalScroll, since that would conflict with their own LazyColumn).
            else -> Column(
                modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                attachments.forEach { attachment ->
                    AttachmentItem(
                        attachment = attachment,
                        isRemoving = removingReferenceId == attachment.referenceId,
                        onRemove = { onRemoveAttachment(attachment) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentItem(attachment: VoucherAttachmentRow, isRemoving: Boolean, onRemove: () -> Unit) {
    val context = LocalContext.current
    var showImagePreview by remember(attachment.referenceId) { mutableStateOf(false) }
    val isImage = attachment.mimeType.startsWith("image/")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.shapeMd)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(Radius.shapeSm).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            // A generic file icon for every attachment row (both image and non-image) - the actual
            // decoded thumbnail only ever renders in the full preview on tap (see showImagePreview
            // below), never here, so no image-specific icon dependency is needed for this row.
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(Spacing.sm))

        // weight(1f) is what makes this row adapt from narrow phones to tablet width without a
        // fixed pixel/dp width anywhere (Slice 2, Step 5) - the metadata column always takes
        // exactly the space left after the fixed-size thumbnail and action icons.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = File(attachment.storageReference).name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${formatFileSize(attachment.sizeBytes)} • ${attachment.mimeType}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isRemoving) {
            AppLoader(modifier = Modifier.size(20.dp).padding(horizontal = Spacing.sm))
        } else {
            Row {
                AppIconButton(
                    icon = Icons.Default.Visibility,
                    contentDescription = "View",
                    onClick = {
                        if (!isShareable(attachment)) {
                            Toast.makeText(context, "File not found - it may have been removed from device storage.", Toast.LENGTH_SHORT).show()
                        } else if (isImage) {
                            showImagePreview = true
                        } else {
                            openAttachmentExternally(context, attachment)
                        }
                    }
                )
                AppIconButton(
                    icon = Icons.Default.Share,
                    contentDescription = "Share",
                    onClick = { shareAttachmentSafely(context, attachment) }
                )
                AppIconButton(icon = Icons.Default.Delete, contentDescription = "Remove", onClick = onRemove)
            }
        }
    }

    if (showImagePreview) {
        AppBottomSheet(onDismiss = { showImagePreview = false }) {
            val bitmap = remember(attachment.storageReference) { decodeBoundedBitmap(attachment.storageReference, 1024) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = File(attachment.storageReference).name,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                )
            } else {
                Text(
                    "Could not preview this image - it may be corrupted or unreadable.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(Spacing.md)
                )
            }
        }
    }
}

/** True only for a genuinely present, regular, readable file (Part 2, Steps 1-2) - the shared
 * pre-check both [shareAttachmentSafely] and [openAttachmentExternally] run before touching
 * [FileProvider] at all. */
private fun isShareable(attachment: VoucherAttachmentRow): Boolean {
    val file = File(attachment.storageReference)
    return file.exists() && file.isFile && file.canRead()
}

/** Share via the existing, unmodified [ShareAdapter.buildShareIntent]/FileProvider architecture
 * (Part 1/2) - never a second sharing implementation. Never crashes: a missing/unreadable file
 * shows a clear error before [ShareAdapter] is even called (Steps 1-2), and both the FileProvider
 * URI resolution inside it and launching the chooser are guarded (Steps 7-8) - a share attempt
 * with no compatible app installed, or any other platform failure, never crashes and never claims
 * success when the [Intent] could not be launched. The `file_paths.xml` root covering
 * `voucher_attachments/` is what actually makes [FileProvider] resolve without throwing here -
 * see that file's own doc comment for the crash this fixed. */
private fun shareAttachmentSafely(context: Context, attachment: VoucherAttachmentRow) {
    if (!isShareable(attachment)) {
        Toast.makeText(context, "File not found - nothing to share.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val intent = ShareAdapter.buildShareIntent(context, File(attachment.storageReference), attachment.mimeType)
        context.startActivity(Intent.createChooser(intent, "Share attachment"))
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "No app available to share this file.", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share this file.", Toast.LENGTH_SHORT).show()
    }
}

/** "Open externally" for a non-image attachment (a PDF, most commonly) - Step 6 forbids inventing
 * a PDF renderer, so this delegates to whatever the OS/an installed app already handles, exactly
 * as instructed ("reuse the existing Android/project capability"). Never crashes if nothing can
 * open it (Part 2, Step 8). */
private fun openAttachmentExternally(context: Context, attachment: VoucherAttachmentRow) {
    if (!isShareable(attachment)) {
        Toast.makeText(context, "File not found - nothing to open.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val file = File(attachment.storageReference)
        val uri = FileProvider.getUriForFile(context, ShareAdapter.FILE_PROVIDER_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open attachment"))
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "No app available to open this file.", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open this file.", Toast.LENGTH_SHORT).show()
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/**
 * Bounded preview decode (Slice 2, Step 6) - never loads a full-resolution [Bitmap] just to show a
 * thumbnail/preview; bounds the *larger* dimension to [maxDimensionPx] via a power-of-2
 * `inSampleSize` (the standard Android-recommended pattern, no dependency). Returns null (never
 * throws) for a corrupt, truncated, or unreadable file - callers always have a safe fallback. This
 * is the one reusable seam Step 11 asks to keep open for a future shared media-processing pipeline
 * (OCR/document scanning consuming the same durable source asset) - deliberately not built further
 * than this single bounded-decode utility in this slice.
 */
internal fun decodeBoundedBitmap(path: String, maxDimensionPx: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val largerDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (largerDimension / (sampleSize * 2) >= maxDimensionPx) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeFile(path, decodeOptions)
    } catch (e: Exception) {
        null
    }
}
