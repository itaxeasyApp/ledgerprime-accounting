package com.example.accounting.data.ocr

import android.graphics.BitmapFactory
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.domain.ocr.DocumentFieldExtractor
import com.example.accounting.domain.ocr.OcrDocumentType
import com.example.accounting.domain.ocr.OcrExtractionResult
import com.example.accounting.domain.ocr.OcrIngestionAdapter
import com.example.accounting.domain.rendering.BusinessProfile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real, on-device implementation of [OcrIngestionAdapter] (Document/Image Scan feature) - Google
 * Play Services' unbundled text recognizer (`play-services-mlkit-text-recognition`), the same
 * "no Firebase project, no API key, no billing" technology millions of Android apps already use
 * for exactly this. Chosen over Firebase AI/Gemini specifically because this app's Firebase
 * project has no `google-services.json` configured yet (same blocker as the deferred phone-number
 * login work) - this adapter has zero such dependency and works today.
 *
 * Lives in `data/`, not `domain/`, for the same layering reason [com.example.accounting.data.qrbarcode.ZxingQrBarcodeAdapter]
 * does: it needs `android.graphics.BitmapFactory` to decode a stored image file. All real field
 * extraction is delegated to [DocumentFieldExtractor] (pure Kotlin, independently testable) - this
 * class's only job is "get an Android [android.graphics.Bitmap] to ML Kit, get plain text back."
 *
 * A future second adapter (e.g. a server-side OCR engine) can implement the same
 * [OcrIngestionAdapter] interface and be composed with this one via [FallbackOcrAdapter] without
 * any change here.
 */
class MlKitOcrAdapter(private val dao: AccountingDao) : OcrIngestionAdapter {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun extractFromDocument(
        requestingCompany: BusinessProfile,
        documentAssetId: String,
        documentTypeHint: OcrDocumentType
    ): AccountingResult<OcrExtractionResult> {
        val asset = dao.getDocumentAssetById(requestingCompany.companyId, documentAssetId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("DocumentAsset", documentAssetId))

        val bitmap = BitmapFactory.decodeFile(File(asset.storageReference).absolutePath)
            ?: return AccountingResult.Failure(AppError.ValidationError("Image asset '${asset.storageReference}' could not be decoded."))

        val rawText = try {
            recognizeText(bitmap)
        } catch (e: Exception) {
            return AccountingResult.Failure(AppError.SystemError("Text recognition failed: ${e.message ?: e.javaClass.simpleName}"))
        }

        if (rawText.isBlank()) {
            return AccountingResult.Failure(AppError.ValidationError("No text could be recognized in this image - try a clearer, better-lit photo."))
        }

        return AccountingResult.Success(DocumentFieldExtractor.extract(documentAssetId, rawText, documentTypeHint))
    }

    private suspend fun recognizeText(bitmap: android.graphics.Bitmap): String = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText -> continuation.resume(visionText.text) }
            .addOnFailureListener { e -> continuation.resumeWithException(e) }
    }
}
