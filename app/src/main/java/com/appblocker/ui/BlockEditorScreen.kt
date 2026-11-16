package com.appblocker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockEditorScreen(onClose: () -> Unit, vm: EditorViewModel = viewModel()) {
    val context = LocalContext.current
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }

    // Load the app list once up front so selected apps can show their icon and label.
    LaunchedEffect(Unit) { vm.loadApps(context.applicationContext.packageManager) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New block" else "Edit block") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back (discard)")
                    }
                },
                actions = {
                    if (!vm.isNew) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete block")
                        }
                    }
                    TextButton(
                        enabled = vm.canSave,
                        onClick = {
                            vm.save()
                            onClose()
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = vm.name,
                onValueChange = { vm.name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Hold time
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Hold time", style = MaterialTheme.typography.titleMedium)
                Text(
                    "How long the button must be held without letting go.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(
                        value = vm.holdMinutes,
                        onChange = { vm.holdMinutes = it },
                        label = "Minutes",
                        isError = !vm.holdValid,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        value = vm.holdSecondsPart,
                        onChange = { vm.holdSecondsPart = it },
                        label = "Seconds",
                        isError = !vm.holdValid,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!vm.holdValid) {
                    Text(
                        "Must be between 1 second and 60 minutes.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Session length
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Session length", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Minutes an unlocked app stays open before you are sent to the home screen. " +
                        "Leaving the app for more than 3 seconds locks it again either way. " +
                        "0 = no time limit.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberField(
                    value = vm.sessionMinutes,
                    onChange = { vm.sessionMinutes = it },
                    label = "Minutes (0 to 600)",
                    isError = !vm.sessionValid,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Apps
            Text("Apps", style = MaterialTheme.typography.titleMedium)
            val appsByPackage = vm.apps?.associateBy { it.packageName }
            if (vm.packages.isEmpty()) {
                Text("No apps selected.", style = MaterialTheme.typography.bodyMedium)
            }
            vm.packages.sortedBy { appsByPackage?.get(it)?.label?.lowercase() ?: it }.forEach { pkg ->
                val app = appsByPackage?.get(pkg)
                ListItem(
                    headlineContent = { Text(app?.label ?: pkg) },
                    // Uninstalled (or still loading): show the package name so it can be removed.
                    supportingContent = if (app == null) {
                        { Text(if (appsByPackage == null) "Loading…" else "Not installed") }
                    } else null,
                    leadingContent = {
                        if (app != null) {
                            Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                        } else {
                            Box(Modifier.size(40.dp))
                        }
                    },
                    trailingContent = {
                        TextButton(onClick = { vm.togglePackage(pkg) }) { Text("Remove") }
                    },
                )
            }
            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Choose apps")
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(vm = vm, onDone = { showPicker = false })
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this block?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.delete()
                    onClose()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/** Digits-only text field (max 4 characters). */
@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> if (new.length <= 4 && new.all { it.isDigit() }) onChange(new) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Full-screen searchable picker. Ticking a box changes the editor's selection immediately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerDialog(vm: EditorViewModel, onDone: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    // Order by what was selected when the picker opened, so rows don't jump while ticking.
    val initiallySelected = remember { vm.packages.toSet() }
    val apps = vm.apps

    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Choose apps") },
                        navigationIcon = {
                            IconButton(onClick = onDone) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = { TextButton(onClick = onDone) { Text("Done") } },
                    )
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (apps == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val shown = remember(apps, query) {
                            val q = query.trim()
                            apps.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }
                                .sortedWith(
                                    compareBy<AppInfo>({ it.packageName !in initiallySelected }, { it.label.lowercase() }),
                                )
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(shown, key = { it.packageName }) { app ->
                                val checked = app.packageName in vm.packages
                                // Ticking an app clears the search, ready to look for the next one.
                                val toggle = {
                                    if (!checked) query = ""
                                    vm.togglePackage(app.packageName)
                                }
                                ListItem(
                                    modifier = Modifier.clickable(onClick = toggle),
                                    headlineContent = { Text(app.label) },
                                    leadingContent = {
                                        Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                                    },
                                    trailingContent = {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { toggle() },
                                        )
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
