package com.ai.assistance.operit.ui.features.chat.webview.workspace

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ImageQrAction(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val links = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var busy by remember(uri) { mutableStateOf(false) }
    var result by remember(uri) { mutableStateOf<String?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    TextButton(modifier = modifier, enabled = !busy, onClick = {
        busy = true
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片尺寸" }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2
                    }
                    val bitmap = requireNotNull(context.contentResolver.openInputStream(uri)).use {
                        requireNotNull(BitmapFactory.decodeStream(it, null, options)) { "无法解码图片" }
                    }
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
                    } finally { bitmap.recycle() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLogger.e("ImageQr", "QR recognition failed", e); error = "未识别到二维码，或图片无法读取" }
            finally { busy = false }
        }
    }) { Text(if (busy) "识别中…" else "识别二维码") }
    val text = result
    if (text != null) AlertDialog(onDismissRequest = { result = null }, title = { Text("二维码内容") }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("复制") } },
        dismissButton = {
            val scheme = Uri.parse(text).scheme
            if (scheme == "https" || scheme == "http") TextButton(onClick = { links.openUri(text) }) { Text("打开网页") }
            else TextButton(onClick = { result = null }) { Text("关闭") }
        })
    if (error != null) AlertDialog(onDismissRequest = { error = null }, text = { Text(requireNotNull(error)) }, confirmButton = {
        TextButton(onClick = { error = null }) { Text("关闭") }
    })
}
