package com.ai.assistance.operit.ui.features.chat.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.model.CustomEmoji
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.WaifuPreferences
import com.ai.assistance.operit.data.repository.CustomEmojiRepository
import com.ai.assistance.operit.data.repository.StickerImageStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StickerPickerDialog(onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CustomEmojiRepository.getInstance(context) }
    val manager = remember { ActivePromptManager.getInstance(context) }
    val activePrompt by manager.activePromptFlow.collectAsState(initial = null)
    val preferences = remember { WaifuPreferences.getInstance(context) }
    val aiEnabled by preferences.waifuEnableEmoticonsFlow.collectAsState(initial = false)
    var aiLibrary by remember { mutableStateOf(false) }
    // null is the independent user library, never a fictitious AI role.
    val target = if (aiLibrary) activePrompt else null
    var category by remember(target) { mutableStateOf<String?>(null) }
    var importCategory by remember { mutableStateOf(context.getString(R.string.sticker_favorites)) }
    var selected by remember(target) { mutableStateOf<CustomEmoji?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val emojis by remember(target) { repository.getAllEmojis(target) }.collectAsState(initial = emptyList())
    val categories = remember(emojis) { emojis.map { it.emotionCategory }.distinct() }
    var pendingTarget by remember { mutableStateOf<ActivePrompt?>(null) }
    var pendingCategory by remember { mutableStateOf("") }
    LaunchedEffect(target) {
        try { repository.initializeBuiltinEmojis(target) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            error = null
            try {
                for (uri in uris) repository.addCustomEmoji(pendingTarget, pendingCategory, uri).getOrThrow()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.sticker_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 510.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !aiLibrary, onClick = { aiLibrary = false }, enabled = !busy,
                        label = { Text(stringResource(R.string.sticker_mine)) })
                    FilterChip(selected = aiLibrary, onClick = { aiLibrary = true }, enabled = !busy && activePrompt != null,
                        label = { Text(stringResource(R.string.sticker_ai_library)) })
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.sticker_ai_enabled), Modifier.weight(1f))
                    Switch(checked = aiEnabled, onCheckedChange = { scope.launch { preferences.saveWaifuEnableEmoticons(it) } })
                }
                Text(stringResource(R.string.sticker_choose_hint), style = MaterialTheme.typography.bodySmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = category == null, onClick = { category = null },
                        label = { Text(stringResource(R.string.sticker_all)) }) }
                    items(categories) { name ->
                        FilterChip(selected = category == name, onClick = { category = name },
                            label = { Text(stickerCategoryLabel(name)) })
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(emojis.filter { category == null || it.emotionCategory == category }, key = { it.id }) { emoji ->
                        AsyncImage(model = repository.getEmojiUri(target, emoji), contentDescription = stickerCategoryLabel(emoji.emotionCategory),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(80.dp).clickable(enabled = !busy) { selected = emoji })
                    }
                }
                OutlinedTextField(value = importCategory, onValueChange = { importCategory = it },
                    label = { Text(stringResource(R.string.sticker_import_category)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                TextButton(enabled = !busy && repository.isValidCategoryName(importCategory), onClick = {
                    pendingTarget = target
                    pendingCategory = importCategory
                    picker.launch("image/*")
                }) { Text(stringResource(R.string.sticker_import)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_close)) } }
    )
    selected?.let { emoji ->
        AlertDialog(onDismissRequest = { if (!busy) selected = null },
            title = { Text(stickerCategoryLabel(emoji.emotionCategory)) },
            text = { AsyncImage(model = repository.getEmojiUri(target, emoji), contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Fit) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val chosenTarget = target
                    scope.launch {
                        busy = true
                        try {
                            val file = withContext(Dispatchers.IO) {
                                StickerImageStorage.snapshot(context, repository.getEmojiFile(chosenTarget, emoji))
                            }
                            val request = Uri.Builder().scheme("operit-sticker").authority("attach")
                                .appendQueryParameter("source", file.absolutePath)
                                .appendQueryParameter("category", emoji.emotionCategory.take(40).trim()).build()
                            onSelect(request.toString())
                            onDismiss()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message; selected = null }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.sticker_add_to_message)) }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = !busy, onClick = {
                        val chosenTarget = target
                        scope.launch {
                            busy = true
                            try { repository.deleteCustomEmoji(chosenTarget, emoji.id).getOrThrow(); selected = null }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message; selected = null }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.delete_emoji)) }
                    TextButton(onClick = { selected = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                }
            })
    }
}

@Composable
fun SaveStickerDialog(source: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CustomEmojiRepository.getInstance(context) }
    var category by remember { mutableStateOf(context.getString(R.string.sticker_favorites)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.sticker_save_to_mine)) },
        text = { Column {
            OutlinedTextField(value = category, onValueChange = { category = it }, singleLine = true,
                label = { Text(stringResource(R.string.sticker_import_category)) }, enabled = !busy)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {
            TextButton(enabled = !busy && repository.isValidCategoryName(category), onClick = {
                scope.launch {
                    busy = true
                    try {
                        repository.addCustomEmoji(null, category, Uri.parse(source)).getOrThrow()
                        Toast.makeText(context, context.getString(R.string.sticker_saved), Toast.LENGTH_SHORT).show()
                        onDismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.sticker_save)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun stickerCategoryLabel(category: String): String = when (category) {
    "happy" -> stringResource(R.string.emoticon_happy)
    "sad" -> stringResource(R.string.sticker_sad)
    "angry" -> stringResource(R.string.emoticon_angry)
    "surprised" -> stringResource(R.string.sticker_surprised)
    "confused" -> stringResource(R.string.sticker_confused)
    "crying" -> stringResource(R.string.sticker_crying)
    "like_you" -> stringResource(R.string.sticker_like_you)
    "miss_you" -> stringResource(R.string.sticker_miss_you)
    "speechless" -> stringResource(R.string.sticker_speechless)
    else -> category
}
