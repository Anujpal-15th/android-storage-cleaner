package dev.storagecleaner

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List as ListIcon
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Root(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshAccess() // the user may be back from a Settings permission page or an uninstall
    }
}

class MainVm(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("settings", 0)

    var hasFiles by mutableStateOf(false); private set
    var hasUsage by mutableStateOf(false); private set
    var scanning by mutableStateOf(false); private set
    var status by mutableStateOf("")
    var result by mutableStateOf<ScanResult?>(null); private set
    val selected = mutableStateMapOf<String, Unit>() // item ids; a map for O(1) lookups while rows recompose
    var apps by mutableStateOf<List<AppStat>?>(null); private set
    var history by mutableStateOf(historyFor(app).all()); private set
    var weekly by mutableStateOf(prefs.getBoolean("weekly", false)); private set
    var totals by mutableStateOf(storageTotals(app)); private set
    var message by mutableStateOf<String?>(null)

    fun refreshAccess() {
        hasFiles = hasFilesAccess(ctx)
        hasUsage = hasUsageAccess(ctx)
        totals = storageTotals(ctx)
        if (apps != null) refreshApps()
    }

    fun scan() {
        if (scanning) return
        scanning = true
        selected.clear()
        viewModelScope.launch {
            result = withContext(Dispatchers.IO) { scannerFor(ctx).scan { status = it } }
            scanning = false
        }
    }

    fun toggle(id: String) {
        if (selected.remove(id) == null) selected[id] = Unit
    }

    fun selectSafe() {
        selected.clear()
        result?.items?.filter { it.level == Level.SAFE }?.forEach { selected[it.id] = Unit }
    }

    fun selectedItems() = result?.items?.filter { it.id in selected }.orEmpty()

    fun clean() {
        val items = selectedItems()
        if (items.isEmpty() || scanning) return
        scanning = true
        status = "Cleaning…"
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                scannerFor(ctx).delete(items).also { historyFor(ctx).add(Cleanup(System.currentTimeMillis(), it, items.size, "Manual")) }
            }
            history = historyFor(ctx).all()
            totals = storageTotals(ctx)
            // Drop what was cleaned instead of re-walking the whole storage (minutes on a full phone).
            // ponytail: the file-type bar is not recomputed; "Scan again" refreshes it.
            val gone = items.map { it.id }.toSet()
            result = result?.let { r -> r.copy(items = r.items.filterNot { it.id in gone }, bytes = r.bytes - freed) }
            selected.clear()
            message = "Freed ${fmt(freed)}"
            scanning = false
        }
    }

    private var appsJob: Job? = null

    fun refreshApps() {
        appsJob?.cancel() // a resume and a tab switch can ask at once; keep only the latest
        appsJob = viewModelScope.launch { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    }

    fun changeWeekly(on: Boolean) {
        weekly = on
        prefs.edit().putBoolean("weekly", on).apply()
        CleanWorker.schedule(ctx, on)
    }
}

// ---------- look ----------

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Same semantic colours as the Windows app, readable in both themes. */
@Composable
fun levelColor(l: Level): Color {
    val dark = isSystemInDarkTheme()
    return when (l) {
        Level.SAFE -> if (dark) Color(0xFF6CCB5F) else Color(0xFF0F7B0F)
        Level.CAUTION -> if (dark) Color(0xFFFCE100) else Color(0xFF9D5D00)
        Level.PROTECTED -> if (dark) Color(0xFFFF99A4) else Color(0xFFC42B1C)
    }
}

private val TYPE_COLORS = mapOf(
    "Images" to Color(0xFF3B82F6), "Videos" to Color(0xFFA855F7), "Audio" to Color(0xFF14B8A6),
    "Documents" to Color(0xFFF59E0B), "Archives & APKs" to Color(0xFFEF4444), "Other" to Color(0xFF94A3B8),
)

private val muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

// ---------- screens ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(vm: MainVm) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }
    val tabs = listOf("Clean" to Icons.Filled.Delete, "Apps" to Icons.AutoMirrored.Filled.ListIcon, "More" to Icons.Filled.Settings)
    Scaffold(
        topBar = { TopAppBar(title = { Text("Storage Cleaner", fontWeight = FontWeight.SemiBold) }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> CleanTab(vm)
                1 -> AppsTab(vm)
                else -> MoreTab(vm)
            }
        }
    }
}

@Composable
fun AccessCard(title: String, text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = muted)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClick) { Text(button) }
    }
}

@Composable
fun CleanTab(vm: MainVm) {
    val ctx = LocalContext.current
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshAccess() }
    if (!vm.hasFiles) {
        AccessCard("Allow file access",
            "Storage Cleaner needs to see your files to find junk, duplicates, old installers and leftovers of removed apps. " +
                "Nothing is deleted until you confirm.", "Grant access") {
            if (Build.VERSION.SDK_INT >= 30)
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            else legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
        return
    }
    LaunchedEffect(Unit) { if (vm.result == null) vm.scan() }
    var confirm by remember { mutableStateOf(false) }
    val r = vm.result
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "overview") { Overview(vm) }
            if (r != null) {
                for (level in listOf(Level.SAFE, Level.CAUTION)) {
                    val its = r.items.filter { it.level == level }
                    if (its.isEmpty()) continue
                    item(key = "h$level") { LevelHeader(level, its.sumOf { it.bytes }) }
                    for ((cat, group) in its.groupBy { it.category }) {
                        item(key = "c$level$cat") {
                            Text(cat, style = MaterialTheme.typography.titleSmall, color = muted, modifier = Modifier.padding(top = 4.dp))
                        }
                        items(group, key = { it.id }) { ItemRow(it, it.id in vm.selected) { vm.toggle(it.id) } }
                    }
                }
                if (r.items.isEmpty()) item { Text("Nothing to clean. Your storage is tidy.") }
            }
        }
        val sel = vm.selectedItems()
        if (sel.isNotEmpty()) Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${sel.size} selected · ${fmt(sel.sumOf { it.bytes })}", Modifier.weight(1f))
                Button(onClick = { confirm = true }, enabled = !vm.scanning) { Text("Clean") }
            }
        }
    }
    if (confirm) ConfirmDialog(vm.selectedItems(), onDismiss = { confirm = false }) {
        confirm = false
        vm.clean()
    }
}

@Composable
fun Overview(vm: MainVm) {
    val (total, free) = vm.totals
    val used = total - free
    val r = vm.result
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${fmt(used)} used of ${fmt(total)}", style = MaterialTheme.typography.titleMedium)
            Text("${fmt(free)} free", style = MaterialTheme.typography.bodySmall, color = muted)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { if (total > 0) used.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
            if (r != null && r.bytes > 0) {
                val types = r.byType.entries.filter { it.value > 0 }.sortedByDescending { it.value }
                Spacer(Modifier.height(16.dp))
                Text("Your files: ${fmt(r.bytes)} in ${r.files} files", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                    types.forEach { e ->
                        Box(Modifier.weight(maxOf(e.value.toFloat() / r.bytes, 0.005f)).fillMaxHeight().background(TYPE_COLORS.getValue(e.key)))
                    }
                }
                Spacer(Modifier.height(6.dp))
                types.chunked(2).forEach { pair ->
                    Row {
                        pair.forEach { e ->
                            Row(Modifier.weight(1f).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(TYPE_COLORS.getValue(e.key)))
                                Text("  ${e.key} ${fmt(e.value)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Stat("Safe to delete", fmt(r.items.filter { it.level == Level.SAFE }.sumOf { it.bytes }), levelColor(Level.SAFE), Modifier.weight(1f))
                    Stat("Needs review", fmt(r.items.filter { it.level == Level.CAUTION }.sumOf { it.bytes }), levelColor(Level.CAUTION), Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.scanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(vm.status, style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedButton(onClick = vm::scan) { Text(if (r == null) "Scan" else "Scan again") }
                    Spacer(Modifier.width(8.dp))
                    if (r != null) TextButton(onClick = vm::selectSafe) { Text("Select all Safe") }
                }
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = muted)
        Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LevelHeader(level: Level, bytes: Long) {
    val safe = level == Level.SAFE
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(levelColor(level)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (safe) "Safe to delete" else "Review first", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (safe) "Apps rebuild this automatically" else "Deletable, but you lose something. Read each note.",
                style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Text(fmt(bytes), color = levelColor(level), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ItemRow(item: Item, checked: Boolean, onToggle: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val base = remember { Environment.getExternalStorageDirectory().path }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.clickable { open = !open }.padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.note, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = if (open) 5 else 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (item.dirsOnly) "${item.targets.size}" else fmt(item.bytes), color = levelColor(item.level), fontWeight = FontWeight.SemiBold)
        }
        if (open) Column(Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp)) {
            item.targets.take(30).forEach {
                Text(it.path.removePrefix(base), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.targets.size > 30) Text("…and ${item.targets.size - 30} more", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun ConfirmDialog(sel: List<Item>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val caution = sel.filter { it.level == Level.CAUTION }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${sel.size} items (${fmt(sel.sumOf { it.bytes })})?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(sel) { Text("• ${it.label}   ${fmt(it.bytes)}", style = MaterialTheme.typography.bodyMedium) }
                if (caution.isNotEmpty()) {
                    item {
                        Text("Review items included:", Modifier.padding(top = 12.dp, bottom = 4.dp),
                            color = levelColor(Level.CAUTION), fontWeight = FontWeight.Bold)
                    }
                    items(caution) { Text("${it.label}: ${it.note}", style = MaterialTheme.typography.bodySmall) }
                }
                item { Text("This can't be undone.", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AppsTab(vm: MainVm) {
    val ctx = LocalContext.current
    if (!vm.hasUsage) {
        AccessCard("Allow usage access",
            "To show how much space each app takes and which apps you haven't opened in 30+ days, " +
                "switch on Usage access for Storage Cleaner.", "Open settings") {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        return
    }
    LaunchedEffect(Unit) { if (vm.apps == null) vm.refreshApps() } // later refreshes come from onResume
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val now = remember { System.currentTimeMillis() }
    val list = vm.apps
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val unused = list.filter { it.unusedDays(now) >= 30 }
    val shown = when (filter) {
        1 -> unused
        2 -> list.sortedByDescending { it.cacheBytes }
        else -> list
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All (${list.size})", "Unused 30+ days (${unused.size})", "Biggest cache").forEachIndexed { i, t ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(t) })
                }
            }
        }
        item {
            Text("Apps use ${fmt(list.sumOf { maxOf(it.total, 0) })}, cache ${fmt(list.sumOf { maxOf(it.cacheBytes, 0) })}. " +
                "Android only lets each app's own settings page clear its cache: tap an app.",
                style = MaterialTheme.typography.bodySmall, color = muted)
        }
        items(shown, key = { it.pkg }) { AppRow(it, now) }
    }
}

@Composable
fun AppRow(a: AppStat, now: Long) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val days = a.unusedDays(now)
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Cache ${fmt(maxOf(a.cacheBytes, 0))}" + if (days >= 30) " · not opened for $days days" else "",
                        style = MaterialTheme.typography.bodySmall, color = if (days >= 30) levelColor(Level.CAUTION) else muted)
                }
                Text(if (a.total >= 0) fmt(a.total) else "?", fontWeight = FontWeight.SemiBold)
            }
            if (open) {
                if (a.appBytes >= 0) Text("App ${fmt(a.appBytes)} · Data ${fmt(a.dataBytes - a.cacheBytes)} · Cache ${fmt(a.cacheBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                    }) { Text("Clear cache…") }
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) }) { Text("Uninstall") }
                }
            }
        }
    }
}

@Composable
fun MoreTab(vm: MainVm) {
    // Android 13+: ask for notifications so the weekly run can say what it freed; schedule either way.
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.changeWeekly(true) }
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Weekly automatic cleanup", style = MaterialTheme.typography.titleMedium)
                        Text("Deletes Safe items only, while the phone is charging. Each run is logged below.",
                            style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    Switch(checked = vm.weekly, onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.changeWeekly(on)
                    })
                }
            }
        }
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text("History", style = MaterialTheme.typography.titleMedium)
                Text("Freed ${fmt(vm.history.sumOf { it.bytes })} in ${vm.history.size} cleanups", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        if (vm.history.isEmpty()) item { Text("No cleanups yet.", color = muted) }
        items(vm.history) { c ->
            ListItem(headlineContent = { Text("${fmt(c.bytes)} freed") },
                supportingContent = { Text("${date.format(Date(c.time))} · ${c.items} items · ${c.source}") })
        }
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("How it stays safe", style = MaterialTheme.typography.titleMedium)
                    for ((lvl, text) in listOf(
                        Level.SAFE to "Safe: rebuildable data like thumbnails, temp files, logs and caches.",
                        Level.CAUTION to "Review: deletable, but you lose something. The note on each item says what.",
                        Level.PROTECTED to "Never deleted: whole standard folders (DCIM, Pictures, Android/media…) and .nomedia markers.",
                    )) Text(text, Modifier.padding(top = 6.dp), color = levelColor(lvl), style = MaterialTheme.typography.bodySmall)
                    Text("Links are never followed. Duplicates always keep one copy. Leftovers must be untouched for 30 days. " +
                        "Nothing leaves your phone.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
    }
}
