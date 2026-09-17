package com.example.accounting.presentation.features.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.accounting.presentation.components.decodeBoundedBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The actual scanned photo, shown above [OcrPreviewCard] - reuses
 * [com.example.accounting.presentation.components.decodeBoundedBitmap] verbatim, the exact bounded-
 * decode utility that file's own doc comment already earmarked for "a future shared media-processing
 * pipeline (OCR/document scanning consuming the same durable source asset)" - never a second image-
 * decoding path. [imagePath] is the already-created source asset's own file path
 * ([com.example.accounting.presentation.viewmodel.AccountingUiState.lastOcrSourceImagePath]) - `null`
 * or an unreadable file shows a plain fallback rather than crashing or leaving blank space.
 */
@Composable
fun OcrScannedImagePreview(imagePath: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(imagePath) { imagePath?.let { decodeBoundedBitmap(it, 1024) } }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Scanned document",
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Scanned image preview isn't available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Wraps [OcrScannedImagePreview] with the "Crop / Rotate" entry point the user asked for - stays a
 * plain read-only preview (unchanged) until tapped, then swaps in [OcrImageEditor] in place. Saving
 * an edit calls [onImageSaved] with the new file's path so the caller (here, [OcrResultScreen]) can
 * both re-render the preview from it and persist it back into
 * [com.example.accounting.presentation.viewmodel.AccountingUiState.lastOcrSourceImagePath] - the
 * edited image, never the original, is what "Apply" and the on-screen preview both use afterwards.
 * `null` imagePath (no source asset) skips straight to the same fallback [OcrScannedImagePreview]
 * already shows - nothing to edit.
 */
@Composable
fun OcrEditableImagePreview(
    imagePath: String?,
    onImageSaved: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditing by remember(imagePath) { mutableStateOf(false) }
    if (imagePath == null) {
        OcrScannedImagePreview(imagePath, modifier)
        return
    }
    if (isEditing) {
        OcrImageEditor(
            imagePath = imagePath,
            onSaved = { newPath -> isEditing = false; onImageSaved(newPath) },
            onCancel = { isEditing = false },
            modifier = modifier
        )
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            OcrScannedImagePreview(imagePath)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { isEditing = true }) {
                Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Crop / Rotate")
            }
        }
    }
}

/**
 * The actual crop-and-rotate editor - Rotate Left/Right apply immediately to the live preview
 * (standard 90 degree turns), and the white-bordered box is a draggable crop rectangle (drag any
 * corner). Save re-decodes the source file at full resolution (never the downsampled preview
 * bitmap), applies the same rotation + crop, and writes a brand-new JPEG to cache - the original
 * scanned file is never overwritten, so Cancel always leaves it untouched. This performs its own
 * decode/rotate/crop/encode, off the main thread, deliberately not reusing [decodeBoundedBitmap]'s
 * caller-side bitmap for the save path (that one is capped at preview resolution).
 */
@Composable
fun OcrImageEditor(
    imagePath: String,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rotationDegrees by remember(imagePath) { mutableStateOf(0) }
    val baseBitmap = remember(imagePath) { decodeBoundedBitmap(imagePath, 1024) }
    val previewBitmap = remember(baseBitmap, rotationDegrees) { baseBitmap?.let { rotateBitmap(it, rotationDegrees) } }
    var cropLeft by remember(imagePath, rotationDegrees) { mutableStateOf(0f) }
    var cropTop by remember(imagePath, rotationDegrees) { mutableStateOf(0f) }
    var cropRight by remember(imagePath, rotationDegrees) { mutableStateOf(1f) }
    var cropBottom by remember(imagePath, rotationDegrees) { mutableStateOf(1f) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    var isSaving by remember { mutableStateOf(false) }

    if (previewBitmap == null) {
        OcrScannedImagePreview(imagePath, modifier)
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Adjust Image", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(previewBitmap.width.toFloat() / previewBitmap.height.toFloat())
                .onGloballyPositioned { containerSize = Size(it.size.width.toFloat(), it.size.height.toFloat()) }
        ) {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = "Scanned document being edited",
                modifier = Modifier.fillMaxSize()
            )
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = Color.White,
                    topLeft = androidx.compose.ui.geometry.Offset(cropLeft * size.width, cropTop * size.height),
                    size = Size((cropRight - cropLeft) * size.width, (cropBottom - cropTop) * size.height),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            CropHandle(fractionX = cropLeft, fractionY = cropTop, containerSize = containerSize) { dx, dy ->
                cropLeft = (cropLeft + dx / containerSize.width).coerceIn(0f, cropRight - 0.1f)
                cropTop = (cropTop + dy / containerSize.height).coerceIn(0f, cropBottom - 0.1f)
            }
            CropHandle(fractionX = cropRight, fractionY = cropTop, containerSize = containerSize) { dx, dy ->
                cropRight = (cropRight + dx / containerSize.width).coerceIn(cropLeft + 0.1f, 1f)
                cropTop = (cropTop + dy / containerSize.height).coerceIn(0f, cropBottom - 0.1f)
            }
            CropHandle(fractionX = cropLeft, fractionY = cropBottom, containerSize = containerSize) { dx, dy ->
                cropLeft = (cropLeft + dx / containerSize.width).coerceIn(0f, cropRight - 0.1f)
                cropBottom = (cropBottom + dy / containerSize.height).coerceIn(cropTop + 0.1f, 1f)
            }
            CropHandle(fractionX = cropRight, fractionY = cropBottom, containerSize = containerSize) { dx, dy ->
                cropRight = (cropRight + dx / containerSize.width).coerceIn(cropLeft + 0.1f, 1f)
                cropBottom = (cropBottom + dy / containerSize.height).coerceIn(cropTop + 0.1f, 1f)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { rotationDegrees = (rotationDegrees + 270) % 360 }, enabled = !isSaving) {
                Icon(Icons.Default.RotateLeft, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Rotate Left")
            }
            OutlinedButton(onClick = { rotationDegrees = (rotationDegrees + 90) % 360 }, enabled = !isSaving) {
                Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Rotate Right")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Drag a corner to crop.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    isSaving = true
                    scope.launch {
                        val savedPath = withContext(Dispatchers.IO) {
                            saveEditedOcrImage(context.cacheDir, imagePath, rotationDegrees, cropLeft, cropTop, cropRight, cropBottom)
                        }
                        isSaving = false
                        if (savedPath != null) onSaved(savedPath)
                    }
                },
                enabled = !isSaving
            ) { Text(if (isSaving) "Saving..." else "Save") }
            TextButton(onClick = onCancel, enabled = !isSaving) { Text("Cancel") }
        }
    }
}

/** One draggable corner handle for [OcrImageEditor]'s crop rectangle, positioned as a fraction of
 * [containerSize] (the image Box's own measured size) and reporting raw drag deltas in px - the
 * caller decides how each corner's drag clamps against the opposite edge. */
@Composable
private fun CropHandle(fractionX: Float, fractionY: Float, containerSize: Size, onDrag: (dx: Float, dy: Float) -> Unit) {
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 10.dp.toPx() }
    Box(
        modifier = Modifier
            .size(20.dp)
            .offset {
                IntOffset(
                    (fractionX * containerSize.width - handleRadiusPx).toInt(),
                    (fractionY * containerSize.height - handleRadiusPx).toInt()
                )
            }
            .clip(CircleShape)
            .background(Color.White)
            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    )
}

private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** Re-decodes [sourcePath] at a higher (but still bounded) resolution than the on-screen preview,
 * applies the same rotation + crop the user set up there, and writes the result as a new JPEG in
 * [cacheDir] - never overwriting [sourcePath], so a cancelled edit leaves the original scan intact. */
private fun saveEditedOcrImage(
    cacheDir: File,
    sourcePath: String,
    rotationDegrees: Int,
    cropLeft: Float,
    cropTop: Float,
    cropRight: Float,
    cropBottom: Float
): String? {
    val fullBitmap = decodeBoundedBitmap(sourcePath, 2048) ?: return null
    val rotated = rotateBitmap(fullBitmap, rotationDegrees)
    val x = (cropLeft * rotated.width).toInt().coerceIn(0, rotated.width - 1)
    val y = (cropTop * rotated.height).toInt().coerceIn(0, rotated.height - 1)
    val w = ((cropRight - cropLeft) * rotated.width).toInt().coerceIn(1, rotated.width - x)
    val h = ((cropBottom - cropTop) * rotated.height).toInt().coerceIn(1, rotated.height - y)
    val cropped = Bitmap.createBitmap(rotated, x, y, w, h)
    val outFile = File(cacheDir, "ocr_edited_${System.currentTimeMillis()}.jpg")
    return try {
        FileOutputStream(outFile).use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        outFile.absolutePath
    } catch (e: Exception) {
        null
    }
}

/**
 * Reusable "read this before you commit it" preview for a reviewed OCR field set - shown before
 * the user taps Edit, so the very first thing they see after a scan is a clean, card-like summary
 * of what was recognized (the "preview of the card" the reviewed document represents), not a bank
 * of open text fields. [fields] is display-only ((label, value) pairs) - this composable performs
 * no extraction/calculation of its own, same as every other OCR review surface in this app.
 */
@Composable
fun OcrPreviewCard(
    title: String,
    fields: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Spacer(modifier = Modifier.height(10.dp))
            fields.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
                    Text(
                        value.ifBlank { "-" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                    )
                }
            }
        }
    }
}

/**
 * Reusable action row for [OcrPreviewCard]'s **preview** state - Edit (switches to the existing
 * editable field form), the caller's own real commit action (e.g. "Apply to Profile"/"Apply to
 * Business Profile" - unchanged, still the only thing that writes anywhere), and Delete (discards
 * this scan). Delete never deletes anything directly itself - see [OcrDeleteConfirmDialog], the
 * small confirmation dialog every destructive action in this app already goes through.
 */
@Composable
fun OcrPreviewActions(
    applyLabel: String,
    applyEnabled: Boolean,
    onEdit: () -> Unit,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.width(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Edit")
        }
        Button(onClick = onApply, enabled = applyEnabled) { Text(applyLabel) }
        OutlinedButton(onClick = onDelete, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.width(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Delete")
        }
    }
}

/**
 * Reusable action row for [OcrPreviewCard]'s **edit** state (the existing OutlinedTextField form
 * is unchanged - this is just its Save/Cancel footer). Save never writes anywhere by itself - it
 * only returns to the preview state with the locally-edited values, exactly like editing a form
 * draft before the real "Apply to Profile" commit.
 */
@Composable
fun OcrEditActions(onSave: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave) { Text("Save") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/**
 * The one small popup this OCR review flow uses - per this app's own rule that dialogs are only
 * for delete/discard/critical confirmations, never a main workflow. Confirming calls [onConfirm],
 * which the caller wires to the same [OcrResultScreen] `onDismiss` every non-destructive dismiss
 * already uses (clears the extraction, navigates back) - deleting a not-yet-applied scan is
 * exactly a discard, never a second deletion pathway.
 */
@Composable
fun OcrDeleteConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete this scan?") },
        text = { Text("This discards the scanned document and its extracted details. Nothing has been applied to your profile yet.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
