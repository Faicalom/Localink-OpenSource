package com.localbridge.android.ui.widgets

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.localbridge.android.models.TransferItem
import com.localbridge.android.ui.LocalAppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

@Composable
fun TransferPreviewPane(
    transfer: TransferItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val previewBitmap = produceState<ImageBitmap?>(initialValue = null, transfer.previewPath, transfer.isImagePreviewCandidate) {
        value = if (transfer.isImagePreviewCandidate && !transfer.previewPath.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                loadPreviewBitmap(context, transfer.previewPath)
            }
        } else {
            null
        }
    }.value

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(152.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        if (previewBitmap != null) {
            Image(
                bitmap = previewBitmap,
                contentDescription = "${transfer.fileName} ${strings["preview_image_unavailable"]}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = strings.transferPreviewFallback(transfer.kind, transfer.mimeType, transfer.fileName),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = transfer.mimeType.ifBlank { "application/octet-stream" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun loadPreviewBitmap(context: Context, previewPath: String?): ImageBitmap? {
    if (previewPath.isNullOrBlank()) {
        return null
    }

    return if (previewPath.startsWith("content://")) {
        decodePreviewFromContentUri(context, previewPath)
    } else {
        decodePreviewFromFile(previewPath)
    }
}

private fun decodePreviewFromContentUri(context: Context, uriString: String): ImageBitmap? {
    val resolver = context.contentResolver
    val uri = uriString.toUri()
    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, boundsOptions)
    } ?: return null

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(boundsOptions, 360, 240)
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }

    return resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, decodeOptions)?.asImageBitmap()
    }
}

private fun decodePreviewFromFile(path: String): ImageBitmap? {
    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    BitmapFactory.decodeFile(path, boundsOptions)
    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
        return null
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(boundsOptions, 360, 240)
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }

    return BitmapFactory.decodeFile(path, decodeOptions)?.asImageBitmap()
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        var halfHeight = height / 2
        var halfWidth = width / 2

        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }

    return max(inSampleSize, 1)
}
