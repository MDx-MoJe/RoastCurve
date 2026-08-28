package com.roastcurve.app.history

import com.roastcurve.shared.l10n.L10n
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roastcurve.shared.model.RoastRecord
import com.roastcurve.shared.storage.RoastStore
import kotlinx.coroutines.launch

/**
 * 烘焙记录列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenRecord: (RoastRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val store = remember { RoastStore() }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<RoastRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        records = store.listAll()
        loading = false
    }

    var pendingDelete by remember { mutableStateOf<RoastRecord?>(null) }

    // 删除确认对话框
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(L10n.get("history.s1")) },
            text = { Text(L10n.get("history.s2")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        store.delete(target.id)
                        records = records.filterNot { it.id == target.id }
                        pendingDelete = null
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L10n.get("history.s3"), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text("返回") }
        }

        Spacer(Modifier.height(8.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            records.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    L10n.get("history.s4"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records, key = { it.id }) { record ->
                    RecordCard(
                        record = record,
                        onClick = { onOpenRecord(record) },
                        onDelete = { pendingDelete = record },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordCard(record: RoastRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // id 形如 20260824-142500 → 显示为日期时间
                val idText = record.id
                val dateText = if (idText.length >= 15) {
                    "${idText.substring(0,4)}-${idText.substring(4,6)}-${idText.substring(6,8)} ${idText.substring(9)}"
                } else idText
                Text(dateText, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append("${record.beanName.ifEmpty { L10n.get("history.s5") }} · ")
                        append(L10n.get("history.s6"))
                        append(L10n.get("history.s7"))
                        if (record.beanWeight > 0f && record.dropWeight > 0f) {
                            val loss = ((record.beanWeight - record.dropWeight) / record.beanWeight * 1000f).toInt() / 10f
                            append(L10n.get("history.s8"))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val endTemp = record.curveData.lastOrNull()?.bt
            if (endTemp != null) {
                Text(
                    "${endTemp.toInt()}°",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDelete) {
                Text("删", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}