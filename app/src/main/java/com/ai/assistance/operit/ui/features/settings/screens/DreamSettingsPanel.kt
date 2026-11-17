package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.companion.DreamSettings
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import kotlinx.coroutines.*

@Composable
internal fun DreamSettingsPanel(role: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { ModelConfigManager(context) }
    val configs by manager.configSummariesFlow.collectAsState(initial = emptyList())
    var binding by remember(role) { mutableStateOf<DreamSettings.Binding?>(null) }
    var ready by remember(role) { mutableStateOf(false) }
    var picking by remember(role) { mutableStateOf(false) }
    var saving by remember(role) { mutableStateOf(false) }
    var error by remember(role) { mutableStateOf("") }
    LaunchedEffect(role) {
        try { binding = withContext(Dispatchers.IO) { DreamSettings(context).read(role) }; ready = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message.orEmpty() }
    }
    fun save(choice: DreamSettings.Binding?) {
        scope.launch {
            saving = true
            try {
                withContext(Dispatchers.IO) { DreamSettings(context).save(role, choice) }
                binding = choice; picking = false; error = ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { saving = false }
        }
    }
    val current = binding
    val config = configs.firstOrNull { it.id == current?.configId }
    val label = if (current == null) stringResource(R.string.dream_model_same)
        else config?.let { c -> getModelList(c.modelName).getOrNull(current.index)?.let { "${c.name} · $it" } }
            ?: stringResource(R.string.dream_model_missing)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dream_settings_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.dream_lottery_description))
            Text(stringResource(R.string.dream_model_description))
            Text(label)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(enabled = ready && !saving, onClick = { picking = true }) { Text(stringResource(R.string.dream_model_choose)) }
        }
    }
    if (picking) AlertDialog(onDismissRequest = { if (!saving) picking = false },
        title = { Text(stringResource(R.string.dream_model_choose)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { TextButton(enabled = !saving, onClick = { save(null) }) { Text(stringResource(R.string.dream_model_same)) } }
                configs.forEach { c -> getModelList(c.modelName).forEachIndexed { index, name ->
                    item(key = "${c.id}:$index") {
                        TextButton(enabled = !saving, onClick = { save(DreamSettings.Binding(c.id, index)) }) { Text("${c.name} · $name") }
                    }
                } }
                if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {}, dismissButton = {
            TextButton(enabled = !saving, onClick = { picking = false }) { Text(stringResource(R.string.dream_settings_close)) }
        })
}
