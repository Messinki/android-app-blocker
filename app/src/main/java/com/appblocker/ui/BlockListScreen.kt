package com.appblocker.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appblocker.data.Block
import com.appblocker.data.BlockRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockListScreen(onOpenBlock: (id: String?) -> Unit) {
    val context = LocalContext.current
    val blocks by BlockRepository.blocks.collectAsStateWithLifecycle()

    // Re-check whenever we come back to the screen (e.g. from system accessibility settings).
    var serviceEnabled by remember { mutableStateOf(isBlockerServiceEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        serviceEnabled = isBlockerServiceEnabled(context)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("App Blocker") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenBlock(null) }) {
                Icon(Icons.Default.Add, contentDescription = "New block")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            // Extra bottom space so the FAB never covers the last row.
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            if (!serviceEnabled) {
                item {
                    ServiceCard(onOpenSettings = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    })
                }
            }
            if (blocks.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No blocks yet. Tap + to group some apps behind a hold-to-open screen.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            items(blocks, key = { it.id }) { block ->
                ListItem(
                    modifier = Modifier.clickable { onOpenBlock(block.id) },
                    headlineContent = { Text(block.name.ifBlank { "Untitled block" }) },
                    supportingContent = { Text(summaryLine(block)) },
                    trailingContent = {
                        Switch(
                            checked = block.enabled,
                            onCheckedChange = { BlockRepository.upsert(block.copy(enabled = it)) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ServiceCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Blocking is off", style = MaterialTheme.typography.titleMedium)
            Text("App Blocker can't block anything until its accessibility service is turned on.")
            Button(onClick = onOpenSettings) { Text("Open accessibility settings") }
            Spacer(Modifier.height(4.dp))
            Text(
                "On Android 13+ a sideloaded app may show the toggle greyed out. Fix: phone Settings " +
                    "→ Apps → App Blocker → ⋮ menu → \"Allow restricted settings\".",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun summaryLine(block: Block): String {
    val n = block.packages.size
    val apps = if (n == 1) "1 app" else "$n apps"
    return "$apps · hold ${formatHold(block.holdSeconds)} · ${block.sessionMinutes} min session"
}

private fun formatHold(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return when {
        m == 0 -> "$s s"
        s == 0 -> "$m min"
        else -> "$m min $s s"
    }
}
