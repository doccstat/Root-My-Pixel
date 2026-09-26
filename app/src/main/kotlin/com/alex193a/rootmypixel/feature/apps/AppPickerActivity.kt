package com.alex193a.rootmypixel.feature.apps

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alex193a.rootmypixel.R
import com.alex193a.rootmypixel.ui.theme.RootMyPixelTheme
import com.alex193a.rootmypixel.utils.AppBackupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chooses which apps the one-button "back up, remove root, reboot clean" flow
 * should archive and remove, and which the next re-root restores.
 *
 * Only user-installed apps are listed: their APKs can be reinstalled from the
 * archive, whereas system/priv-app packages cannot be restored that way.
 */
class AppPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RootMyPixelTheme {
                AppPickerScreen(
                    loadApps = {
                        val pm = packageManager
                        val archived = AppBackupStore.archivedPackages(this)
                        pm.getInstalledApplications(0)
                            .filter {
                                it.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
                                    it.packageName != packageName
                            }
                            .map {
                                AppEntry(
                                    label = it.loadLabel(pm).toString(),
                                    packageName = it.packageName,
                                    archived = archived.contains(it.packageName),
                                )
                            }
                            .sortedBy { it.label.lowercase() }
                    },
                    loadSelection = { AppBackupStore.loadPlan(this).toSet() },
                    loadExtraPaths = { AppBackupStore.loadExtraPaths(this) },
                    onSave = { selected, extraPaths ->
                        AppBackupStore.savePlan(this, selected.toList())
                        AppBackupStore.saveExtraPaths(this, extraPaths)
                        finish()
                    },
                )
            }
        }
    }
}

private data class AppEntry(
    val label: String,
    val packageName: String,
    val archived: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerScreen(
    loadApps: () -> List<AppEntry>,
    loadSelection: () -> Set<String>,
    loadExtraPaths: () -> List<String>,
    onSave: (Set<String>, List<String>) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var extraText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (loadedApps, loadedSelection, loadedExtras) = withContext(Dispatchers.IO) {
            Triple(loadApps(), loadSelection(), loadExtraPaths())
        }
        apps = loadedApps
        selected = loadedSelection
        extraText = loadedExtras.joinToString("\n")
        loaded = true
    }

    val visible = remember(apps, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) apps
        else apps.filter {
            it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = stringResource(R.string.app_picker_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.app_picker_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_picker_selected, selected.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                if (!loaded) {
                    Text(
                        text = stringResource(R.string.app_picker_loading),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(visible, key = { it.packageName }) { entry ->
                    val checked = selected.contains(entry.packageName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - entry.packageName
                                else selected + entry.packageName
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = entry.packageName + if (entry.archived) " • backed up" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                    )
                }
            }

            OutlinedTextField(
                value = extraText,
                onValueChange = { extraText = it },
                minLines = 2,
                maxLines = 4,
                label = { Text(stringResource(R.string.app_picker_extra_dirs)) },
                supportingText = { Text(stringResource(R.string.app_picker_extra_dirs_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { selected = emptySet() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.app_picker_clear))
                }
                Button(
                    onClick = {
                        onSave(
                            selected,
                            extraText.split('\n', ',').map(String::trim)
                                .filter { it.startsWith("/") && it.length > 1 },
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.app_picker_save))
                }
            }
        }
    }
}
