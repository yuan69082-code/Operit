package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.companion.ReadingRoom
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal val BookInk = Color(0xFF263E36)
internal val BookPaper = Color(0xFFFAF4E8)
private val BookGold = Color(0xFFBA955B)
internal val ReadingHandwriting = FontFamily(Font(R.font.reading_handwriting))

@Composable
internal fun ReadingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = BookInk, onPrimary = BookPaper,
        surface = BookPaper, onSurface = BookInk, surfaceVariant = Color(0xFFEDE5D5),
        onSurfaceVariant = Color(0xFF6A7064), outline = Color(0xFF9A9C88)), content = content)
}

/** Books are real records. The background is decoration and never contributes article content. */
@Composable
internal fun BookshelfLayer(label: String, entries: List<JSONObject>, empty: String, diary: Boolean = false, open: (JSONObject) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontFamily = FontFamily.Serif, fontSize = 23.sp, color = BookInk)
            Text("${entries.size} 本", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            if (entries.size > 3) Text("左右滑动 ›", style = MaterialTheme.typography.labelSmall)
        }
        Box(Modifier.fillMaxWidth().height(236.dp)) {
            Image(painterResource(R.drawable.reading_shelf), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            if (entries.isEmpty()) {
                Text(empty, Modifier.align(Alignment.Center).padding(horizontal = 44.dp)
                    .background(BookPaper.copy(alpha = .9f), RoundedCornerShape(12.dp)).padding(18.dp),
                    color = BookInk, fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
            } else {
                LazyRow(Modifier.fillMaxWidth().height(208.dp).align(Alignment.TopStart),
                    contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    itemsIndexed(entries, key = { _, entry -> entry.getString("id") }) { index, entry ->
                        BookSpine(entry, index, diary) { open(entry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookSpine(entry: JSONObject, index: Int, diary: Boolean, open: () -> Unit) {
    val title = entry.getString("title")
    val palette = listOf(Color(0xFF263F4D), Color(0xFF495B44), Color(0xFFCFBE9C), Color(0xFF6D8791), Color(0xFF695349))
    val color = palette[Math.floorMod(entry.getString("id").hashCode(), palette.size)]
    val ink = if (color == palette[2]) BookInk else BookPaper
    val glyphs = title.codePoints().toArray().map { String(Character.toChars(it)) }
    val spineTitle = (if (glyphs.size > 6) glyphs.take(5) + "⋮" else glyphs).joinToString("\n")
    val shape = RoundedCornerShape(topStart = 5.dp, topEnd = 7.dp, bottomEnd = 2.dp, bottomStart = 3.dp)
    Box(Modifier.width(62.dp).height((180 + index % 3 * 6).dp)
        .shadow(5.dp, shape).clip(shape)
        .background(Brush.horizontalGradient(listOf(color.copy(alpha = .85f), color, color, color.copy(alpha = .86f))))
        .border(.6.dp, ink.copy(alpha = .3f), shape)
        .semantics(mergeDescendants = true) { contentDescription = "${if (diary) entry.optString("date") + "，" else ""}$title，打开书页" }
        .clickable(onClick = open)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .26f), Color.Transparent, Color.White.copy(alpha = .12f))), size = size.copy(width = 9.dp.toPx()))
            val gold = BookGold.copy(alpha = .65f)
            listOf(8f, 12f, size.height / density - 13f).forEach { y -> drawLine(gold, Offset(9.dp.toPx(), y.dp.toPx()), Offset(size.width - 6.dp.toPx(), y.dp.toPx()), .7.dp.toPx()) }
            // Fine cloth grain and a botanical foil mark make each binding tactile without network assets.
            for (x in 12..60 step 3) drawLine(ink.copy(alpha = .045f), Offset(x.dp.toPx(), 0f), Offset(x.dp.toPx(), size.height), .5.dp.toPx())
        }
        Column(Modifier.fillMaxSize().padding(top = 20.dp, bottom = 18.dp, start = 8.dp, end = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(spineTitle, color = ink, fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f), maxLines = 6)
            Text(if (diary) entry.optString("date").takeLast(5).replace('-', '/') else "✧", color = ink.copy(alpha = .85f), fontSize = 10.sp)
        }
    }
}

@Composable
internal fun ReadingBookDialog(title: String, subtitle: String, dismiss: () -> Unit, content: @Composable ColumnScope.(Int) -> Unit) {
    var textSize by rememberSaveable { mutableIntStateOf(18) }
    var sizing by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReadingTheme {
            Surface(Modifier.fillMaxSize(), color = Color(0xFFE0D6C1)) {
                Box(Modifier.safeDrawingPadding().imePadding().padding(start = 7.dp, end = 7.dp, bottom = 7.dp)) {
                    Box(Modifier.matchParentSize().offset(y = 4.dp).clip(RoundedCornerShape(6.dp, 20.dp, 24.dp, 6.dp)).background(Color(0xFFC8B898)))
                    Column(Modifier.fillMaxSize().shadow(4.dp, RoundedCornerShape(6.dp, 20.dp, 24.dp, 6.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFE7DCC6), BookPaper, BookPaper, Color(0xFFF3EAD8))))) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = dismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书架") }
                            Text("返回书架", Modifier.weight(1f), fontFamily = FontFamily.Serif)
                            IconButton(onClick = { sizing = !sizing }) { Icon(Icons.Default.TextFields, "调整阅读字号") }
                        }
                        if (sizing) Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { textSize -= 2 }, enabled = textSize > 14) { Text("缩小") }
                            Text("${textSize}号", Modifier.weight(1f), textAlign = TextAlign.Center)
                            TextButton(onClick = { textSize += 2 }, enabled = textSize < 28) { Text("放大") }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 23.dp).padding(top = 12.dp, bottom = 36.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            SelectionContainer { Text(title, fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 39.sp, color = BookInk) }
                            Text(subtitle, color = Color(0xFF697366), fontSize = 12.sp, lineHeight = 19.sp)
                            content(textSize)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReadingArticle(item: JSONObject, name: String, textSize: Int, fetch: () -> Unit, source: () -> Unit, saveNote: (String) -> Unit, skip: () -> Unit) {
    val context = LocalContext.current
    // Same public-source DNS policy as article fetch; arbitrary inline non-web resources are never executed.
    val imageLoader = remember(context) { ImageLoader.Builder(context).okHttpClient(ReadingRoom.client).build() }
    DisposableEffect(imageLoader) { onDispose { imageLoader.shutdown() } }
    var userNote by rememberSaveable(item.getString("id")) { mutableStateOf(item.optString("user_note")) }
    var annotate by rememberSaveable(item.getString("id")) { mutableStateOf(false) }
    val blocks = item.optJSONArray("page_blocks")
    if (blocks != null) {
        for (i in 0 until blocks.length()) {
            val block = blocks.getJSONObject(i)
            if (block.getString("type") == "text") BookParagraph(block.getString("text"), textSize)
            else if (block.getString("type") == "image") {
                SubcomposeAsyncImage(model = block.getString("url"), imageLoader = imageLoader, contentDescription = block.optString("alt"),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 300.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                    loading = { Box(Modifier.height(140.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } },
                    error = { Text("这张原文配图暂时未能载入，可到来源查看。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) })
                if (block.optString("alt").isNotBlank()) Text(block.getString("alt"), fontSize = 12.sp, color = Color(0xFF697366))
            }
        }
    } else {
        // Published records did not contain display blocks. Render their stored text without rewriting them.
        val text = if (item.has("body")) item.getString("body") else item.optString("summary")
        if (text.isBlank()) Text("这里只留了链接，还没有取得可阅读的文字。") else BookParagraph(text, textSize)
    }
    if (!item.getString("url").startsWith("hy:")) {
        Button(onClick = source, shape = RoundedCornerShape(12.dp)) { Text("到来源继续阅读 ↗") }
        TextButton(onClick = fetch) { Text(if (item.has("body")) "重新读取网页可用正文" else "读取网页可用正文") }
    } else Text("这是一条 hy 论坛线索，正文由助手通过论坛工具读取。")
    HorizontalDivider(color = BookGold.copy(alpha = .35f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$name 的随记", fontSize = 13.sp)
        if (item.has("ai_note_at")) Text(bookDate(item.getLong("ai_note_at")), fontSize = 12.sp)
    }
    if (item.optString("note").isBlank()) Text("这一页还没有留下随记。", color = Color(0xFF697366), fontSize = 14.sp)
    else {
        SelectionContainer { Text(item.getString("note"), fontFamily = ReadingHandwriting, fontSize = (textSize + 6).sp, lineHeight = (textSize + 17).sp, color = BookInk) }
        Text(name, Modifier.fillMaxWidth(), fontFamily = ReadingHandwriting, fontSize = 26.sp, textAlign = TextAlign.End)
    }
    HorizontalDivider(color = BookGold.copy(alpha = .35f))
    TextButton(onClick = { annotate = !annotate }) { Text(if (annotate) "收起我的批注" else "我的批注${if (item.optString("user_note").isBlank()) "" else " · 已保存"}") }
    if (annotate) {
        OutlinedTextField(userNote, { userNote = it }, label = { Text("留下你的话") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Button(onClick = { saveNote(userNote) }, enabled = userNote != item.optString("user_note")) { Text("保存批注") }
        if (userNote == item.optString("user_note") && userNote.isNotEmpty()) Text("批注已保存", style = MaterialTheme.typography.labelSmall)
    }
    if (item.optString("state") == "queued") TextButton(onClick = skip) { Text("从待读移开") }
}

@Composable
internal fun BookParagraph(text: String, textSize: Int) {
    SelectionContainer { Text(text, fontFamily = FontFamily.Serif, fontSize = textSize.sp, lineHeight = (textSize * 1.85).sp, color = BookInk) }
}

internal fun bookDate(timestamp: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
