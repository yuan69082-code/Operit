package com.ai.assistance.operit.ui.features.settings.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.ImageLoader
import com.ai.assistance.operit.core.companion.ReadingRoom
import com.ai.assistance.operit.core.companion.StudyStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal fun studyObjects(array: JSONArray): List<JSONObject> = (0 until array.length()).map { array.getJSONObject(it) }
private fun studyFont(font: String): FontFamily = when (font) {
    "hand" -> ReadingHandwriting
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}
internal fun studyInk(hex: String) = Color(android.graphics.Color.parseColor(hex))

@Composable
internal fun StudyPhotos(photos: JSONArray) {
    val context = LocalContext.current
    val loader = remember(context) { ImageLoader.Builder(context).okHttpClient(ReadingRoom.client).build() }
    for (i in 0 until photos.length()) {
        val path = photos.getString(i)
        AsyncImage(model = if (path.startsWith("/")) File(path) else path, imageLoader = loader,
            contentDescription = "作品或批注中的照片", modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 340.dp))
    }
}

@Composable
internal fun StudyDrawing(lines: List<List<Offset>>, color: Color, modifier: Modifier = Modifier, onChange: ((List<List<Offset>>) -> Unit)? = null) {
    var current by remember { mutableStateOf(emptyList<Offset>()) }
    val latestLines by rememberUpdatedState(lines)
    val latestChange by rememberUpdatedState(onChange)
    val gesture = if (onChange == null) Modifier else Modifier.pointerInput(Unit) {
        fun normalized(point: Offset) = Offset((point.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f), (point.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
        detectDragGestures(onDragStart = { current = listOf(normalized(it)) },
            onDrag = { change, _ -> change.consume(); if (current.size < 1200) current = current + normalized(change.position) },
            onDragEnd = { if (current.isNotEmpty() && latestLines.size < 200) latestChange?.invoke(latestLines + listOf(current)); current = emptyList() },
            onDragCancel = { current = emptyList() })
    }
    Canvas(modifier.fillMaxWidth().aspectRatio(1.6f).background(Color.White.copy(alpha = .65f)).then(gesture)) {
        (lines + listOf(current)).forEach { points ->
            if (points.size == 1) drawCircle(color, 2.dp.toPx(), Offset(points[0].x * size.width, points[0].y * size.height))
            else if (points.isNotEmpty()) {
                val path = Path().apply { moveTo(points[0].x * size.width, points[0].y * size.height)
                    points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) } }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}
private fun drawingPoints(array: JSONArray): List<List<Offset>> = (0 until array.length()).map { i ->
    val line = array.getJSONArray(i)
    (0 until line.length()).map { j -> val point = line.getJSONArray(j); Offset(point.getDouble(0).toFloat(), point.getDouble(1).toFloat()) }
}

@Composable
internal fun StudyNote(note: JSONObject, name: String) {
    val color = studyInk(note.getString("color"))
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (note.getString("author") == "user") "你留下的话" else "$name 留下的话", style = MaterialTheme.typography.labelSmall, color = color)
        if (note.getString("text").isNotEmpty()) Text(note.getString("text"), color = color, fontFamily = studyFont(note.getString("font")), fontSize = 19.sp)
        if (note.getJSONArray("lines").length() > 0) StudyDrawing(drawingPoints(note.getJSONArray("lines")), color)
        StudyPhotos(note.getJSONArray("photos"))
    }
}

/** Each paragraph keeps its original UTF-16 offset; notes sit directly below their quoted text. */
@Composable
internal fun StudyAnnotatedText(body: String, version: String, notes: List<JSONObject>, size: Int, name: String, annotate: (Int, Int, String) -> Unit) {
    val paragraphs = remember(body) { Regex("[^\\n]+(?:\\n|$)").findAll(body).toList() }
    paragraphs.forEach { paragraph -> key(version, paragraph.range.first) {
        val start = paragraph.range.first
        val text = paragraph.value
        var selection by remember(version, start) { mutableStateOf(TextRange.Zero) }
        val active = notes.filter { it.optString("version") == version && it.getInt("start") < start + text.length && it.getInt("end") > start }
        val transformation = VisualTransformation { annotated ->
            val builder = AnnotatedString.Builder(annotated)
            active.forEach { note -> builder.addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = studyInk(note.getString("color"))),
                (note.getInt("start") - start).coerceIn(0, text.length), (note.getInt("end") - start).coerceIn(0, text.length)) }
            TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        }
        BasicTextField(value = TextFieldValue(text, selection), onValueChange = { selection = it.selection }, readOnly = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = BookInk, fontFamily = FontFamily.Serif, fontSize = size.sp, lineHeight = (size * 1.7f).sp),
            modifier = Modifier.fillMaxWidth(), visualTransformation = transformation)
        if (!selection.collapsed) TextButton(onClick = {
            val from = minOf(selection.start, selection.end); val end = maxOf(selection.start, selection.end)
            annotate(start + from, start + end, text.substring(from, end))
        }) { Text("给选中文字划线留言") }
        active.filter { it.getInt("end") <= start + text.length }.forEach { StudyNote(it, name) }
    } }
}

@Composable
internal fun StudyNoteEditor(store: StudyStore, book: String, version: String?, start: Int, end: Int, quote: String,
                             busy: Boolean, error: String, run: (suspend () -> Unit) -> Unit, dismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var font by remember { mutableStateOf("hand") }
    var color by remember { mutableStateOf("#486B65") }
    var lines by remember { mutableStateOf(emptyList<List<Offset>>()) }
    var drawing by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf(emptyList<String>()) }
    val operation = remember { UUID.randomUUID().toString() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) run {
        val path = store.addPhoto(uri); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { photos = photos + path }
    } }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReadingTheme { Surface(Modifier.fillMaxWidth().fillMaxHeight(.95f)) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (version == null) "写读后感" else "给原文留下批注", style = MaterialTheme.typography.titleLarge)
                if (quote.isNotBlank()) Text(quote.take(500), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, enabled = !busy, label = { Text("想留下的话") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = studyFont(font), color = studyInk(color)))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("hand" to "手写", "serif" to "衬线", "sans" to "简洁", "mono" to "等宽").forEach { (id, label) -> FilterChip(font == id, { font = id }, label = { Text(label) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("#486B65", "#934E64", "#536C9C", "#9B762F", "#333333").forEach { hex -> FilterChip(color == hex, { color = hex }, label = { Text("●", color = studyInk(hex)) }) }
                }
                Row { TextButton(onClick = { drawing = !drawing }) { Text("手画简笔画") }
                    TextButton(onClick = { picker.launch("image/*") }, enabled = !busy && photos.size < 12) { Text("放照片") } }
                if (drawing) {
                    StudyDrawing(lines, studyInk(color), onChange = { lines = it })
                    Row { TextButton(onClick = { lines = lines.dropLast(1) }) { Text("撤销一笔") }; TextButton(onClick = { lines = emptyList() }) { Text("清空画板") } }
                }
                StudyPhotos(JSONArray(photos))
                if (photos.isNotEmpty()) TextButton(onClick = { photos = photos.dropLast(1) }) { Text("移除最后一张照片") }
                Text("保存后会提醒并唤醒这本书所属的助手", style = MaterialTheme.typography.labelSmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") }
                    Button(enabled = !busy && (text.isNotBlank() || lines.isNotEmpty() || photos.isNotEmpty()), onClick = { run {
                        val strokes = JSONArray(lines.map { line -> JSONArray(line.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) }) })
                        store.annotate(book, version, start, end, quote, text, font, color, strokes, JSONArray(photos), "user", operation)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { dismiss() }
                    } }) { Text("保存并提醒他") } }
            }
        } }
    }
}
