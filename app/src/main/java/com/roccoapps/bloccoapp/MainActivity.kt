package com.roccoapps.bloccoapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.roccoapps.bloccoapp.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

class MainActivity : ComponentActivity() {

    private var refresh by mutableIntStateOf(0)
    private var sharedPkg by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    Root(refresh)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        Policy.apply(this)
        if (Policy.isOwner(this) || Prefs.basicMode(this)) {
            GuardService.start(this)
            Work.schedule(this)
        }
        refresh++
        // Controllo aggiornamenti al massimo ogni ora quando si apre l'app.
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(this) > 3_600_000) {
            Prefs.setLastUpdateCheck(this, System.currentTimeMillis())
            lifecycleScope.launch { runCatching { Updater.check()?.let { doUpdate() } } }
        }
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val pkg = NeverInstall.parse(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")
            if (pkg == null) toast("Link non riconosciuto") else sharedPkg = pkg
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private suspend fun doUpdate() {
        toast("Scarico l'aggiornamento…")
        runCatching {
            val f = Updater.download(this)
            Updater.install(this, f)
        }.onFailure { toast("Aggiornamento non riuscito: ${it.message}") }
    }

    // ---------------------------------------------------------------- UI

    @Composable
    private fun Root(rev: Int) {
        val ctx = this
        when {
            !Policy.isOwner(ctx) && !Prefs.basicMode(ctx) -> SetupScreen(rev)
            !Password.isSet(ctx) -> NewPasswordScreen(needOld = false) { refresh++ }
            else -> MainScreen(rev)
        }
    }

    @Composable
    private fun SetupScreen(rev: Int) {
        val cmd = "adb shell dpm set-device-owner $packageName/.AdminReceiver"
        LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Attivazione (una volta sola)", style = MaterialTheme.typography.headlineSmall) }
            item {
                Text(
                    "Per bloccare davvero le app, Blocco App deve diventare \"proprietario del dispositivo\" " +
                        "(Device Owner). Dopo non si potrà disinstallare né disattivare senza la password."
                )
            }
            item { Text("1. Impostazioni → Account: rimuovi temporaneamente TUTTI gli account (Google, Xiaomi, WhatsApp…). Li riaggiungi subito dopo.") }
            item { Text("2. Attiva Opzioni sviluppatore → Debug USB (su Xiaomi anche \"Debug USB (impostazioni di sicurezza)\").") }
            item { Text("3. Da PC, oppure dal telefono stesso con LADB / Shizuku + aShell tramite Debug wireless, esegui:") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(cmd, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace)
                }
            }
            item {
                Button(onClick = {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("cmd", cmd))
                    toast("Comando copiato")
                }) { Text("Copia comando") }
            }
            item { Text("4. Torna qui: si apre la schermata per impostare la password. Falla scegliere a un'altra persona.") }
            item {
                OutlinedButton(onClick = {
                    if (!Policy.isOwner(this@MainActivity)) toast("Non ancora Device Owner: il comando non è stato eseguito o ha dato errore.")
                    refresh++
                }) { Text("Ho fatto, ricontrolla") }
            }
            item { HorizontalDivider() }
            item { Text("Oppure, senza comando:", fontWeight = FontWeight.Bold) }
            item {
                Text(
                    "Modalità base: le app bloccate si chiudono appena le apri (tramite Accessibilità). " +
                        "È più facile da aggirare: si può disattivare l'accessibilità o disinstallare l'app. " +
                        "Potrai passare a Device Owner quando vuoi, senza perdere le impostazioni."
                )
            }
            item {
                Button(onClick = { Prefs.setBasicMode(this@MainActivity, true); refresh++ }) {
                    Text("Usa modalità base")
                }
            }
            item {
                Text(
                    "Attenzione: se la password viene persa, l'unico modo per togliere il blocco è il ripristino di " +
                        "fabbrica dalla recovery (si perdono i dati del telefono).",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    @Composable
    private fun NewPasswordScreen(needOld: Boolean, onDone: () -> Unit) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (needOld) "Nuova password" else "Imposta la password", style = MaterialTheme.typography.headlineSmall)
            Text("Consiglio: falla scegliere e digitare a un'altra persona, e non fartela dire.")
            PwField(p1, "Password (min 6 caratteri)") { p1 = it }
            PwField(p2, "Ripeti password") { p2 = it }
            Button(
                enabled = p1.length >= 6 && p1 == p2,
                onClick = { Password.set(this@MainActivity, p1); toast("Password salvata"); onDone() }
            ) { Text("Salva") }
        }
    }

    @Composable
    private fun PwField(v: String, label: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = v, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
    }

    // ---- dialoghi condivisi
    private var askPw by mutableStateOf<Pair<String, () -> Unit>?>(null)
    private var confirm by mutableStateOf<Triple<String, String, () -> Unit>?>(null)

    private fun withPassword(reason: String, action: () -> Unit) { askPw = reason to action }
    private fun withConfirm(title: String, text: String, action: () -> Unit) { confirm = Triple(title, text, action) }

    @Composable
    private fun Dialogs() {
        askPw?.let { (reason, action) ->
            var pw by remember { mutableStateOf("") }
            var err by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { askPw = null },
                title = { Text("Password richiesta") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(reason)
                        PwField(pw, "Password") { pw = it }
                        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        when (val r = Password.check(this@MainActivity, pw)) {
                            Password.Result.Ok -> { askPw = null; action(); refresh++ }
                            is Password.Result.Wrong -> err = "Sbagliata. Tentativi prima dell'attesa: ${r.attemptsLeft}"
                            is Password.Result.Locked -> err = "Troppi errori. Riprova tra ${(r.waitMs / 60000) + 1} min"
                        }
                    }) { Text("Conferma") }
                },
                dismissButton = { TextButton(onClick = { askPw = null }) { Text("Annulla") } }
            )
        }
        confirm?.let { (title, text, action) ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(title) },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = { confirm = null; action(); refresh++ }) { Text("Sì, definitivo") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annulla") } }
            )
        }
        sharedPkg?.let { pkg ->
            val ok = NeverInstall.canAdd(this, pkg)
            AlertDialog(
                onDismissRequest = { sharedPkg = null },
                title = { Text("Mai installare?") },
                text = {
                    Text(
                        if (!ok) "$pkg non si può mettere in lista."
                        else "Aggiungo $pkg alla lista \"Mai installare\".\n\nNON si potrà più togliere."
                    )
                },
                confirmButton = {
                    if (ok) TextButton(onClick = {
                        Prefs.addNeverInstall(this, pkg); Policy.apply(this); sharedPkg = null; refresh++
                    }) { Text("Aggiungi per sempre") }
                },
                dismissButton = { TextButton(onClick = { sharedPkg = null }) { Text("Annulla") } }
            )
        }
    }

    // ---- schermata principale

    @Composable
    private fun MainScreen(rev: Int) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(this@MainActivity) } }
        val tabs: List<Pair<String, ImageVector>> = listOf(
            "Oggi" to Icons.Filled.Home,
            "Bloccate" to Icons.Filled.Lock,
            "Limiti" to Icons.Filled.Timer,
            "Mai inst." to Icons.Filled.Block,
            "Altro" to Icons.Filled.Settings,
        )
        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Icon(icon, label) }, label = { Text(label, maxLines = 1) }
                        )
                    }
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
                Text(
                    tabs[tab].first.let { if (tab == 0) "Blocco App" else it },
                    Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                )
                if (!Policy.isOwner(this@MainActivity)) BasicBanner(rev)
                val list = apps
                if (list == null && tab in 1..2) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else when (tab) {
                    0 -> TodayTab(list, rev) { tab = it }
                    1 -> BlockedTab(list!!, rev)
                    2 -> LimitsTab(list!!, rev)
                    3 -> NeverTab(rev)
                    else -> OtherTab(rev)
                }
            }
        }
        Dialogs()
    }

    // ------------------------------------------------------------ Dashboard "Oggi"

    private val labelCache = HashMap<String, String>()
    private fun labelOf(pkg: String, apps: List<AppInfo>?): String =
        apps?.firstOrNull { it.pkg == pkg }?.label ?: labelCache.getOrPut(pkg) {
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg.substringAfterLast('.'))
        }

    @Composable
    private fun TodayTab(apps: List<AppInfo>?, rev: Int, goTo: (Int) -> Unit) {
        var rep by remember { mutableStateOf<UsageReport?>(null) }
        var loaded by remember { mutableStateOf(false) }
        LaunchedEffect(rev) {
            while (true) {
                rep = withContext(Dispatchers.IO) { runCatching { Stats.report(this@MainActivity) }.getOrNull() }
                loaded = true
                delay(60_000)
            }
        }
        val r = rep
        if (!Limits.hasUsageAccess(this)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Per vedere grafici e tempi serve \"Accesso ai dati di utilizzo\".", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                PermissionsCard(rev)
            }
            return
        }
        if (!loaded || r == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        val blocked = Prefs.blocked(this)
        val limits = Prefs.limits(this)
        val over = Limits.suspendedToday(this)
        val top = r.appToday.entries.sortedByDescending { it.value }.filter { it.value >= 60_000 }.take(15)
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (Checks.missing(this@MainActivity) > 0) item { PermissionsCard(rev) }
            item { HeroCard(r, blocked.size, limits.size) }
            item {
                ChartCard("Ultimi 7 giorni") { WeekChart(r) }
            }
            item {
                ChartCard("Oggi, ora per ora") { HourChart(r.hourToday) }
            }
            if (limits.isNotEmpty()) item {
                ChartCard("Limiti di oggi") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        limits.entries.sortedBy { labelOf(it.key, apps) }.forEach { (pkg, min) ->
                            val used = r.appToday[pkg] ?: 0L
                            val frac = (used.toFloat() / (min * 60_000f)).coerceIn(0f, 1f)
                            val done = pkg in over
                            Column {
                                Row {
                                    Text(labelOf(pkg, apps), Modifier.weight(1f))
                                    Text(
                                        if (done) "finito" else "${Stats.fmt(used)} / $min min",
                                        color = if (done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { frac },
                                    Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = if (frac >= 1f || done) MaterialTheme.colorScheme.error
                                    else if (frac > 0.75f) Color(0xFFE6A100) else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text("App più usate oggi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (top.isEmpty()) item { Text("Ancora niente oggi. Bene così.") }
            val max = top.firstOrNull()?.value ?: 1L
            items(top, key = { it.key }) { (pkg, ms) ->
                val info = apps?.firstOrNull { it.pkg == pkg }
                Card(Modifier.fillMaxWidth().clickable { quickPkg = pkg }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        info?.icon?.let { Image(it, null, Modifier.size(40.dp)) } ?: Spacer(Modifier.size(40.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(labelOf(pkg, apps), Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(Stats.fmt(ms), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier.fillMaxWidth((ms.toFloat() / max).coerceIn(0.03f, 1f)).height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(Modifier.height(4.dp))
                            val tags = buildList {
                                add("${r.opensToday[pkg] ?: 0} aperture")
                                if (pkg in blocked) add("🔒 bloccata")
                                limits[pkg]?.let { add("⏱ limite $it min") }
                            }
                            Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Text(
                    "Tocca un'app per bloccarla o darle un limite.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        quickPkg?.let { pkg -> QuickActions(pkg, labelOf(pkg, apps), goTo) }
    }

    private var quickPkg by mutableStateOf<String?>(null)

    @Composable
    private fun QuickActions(pkg: String, label: String, goTo: (Int) -> Unit) {
        val isBlocked = pkg in Prefs.blocked(this)
        val hasLimit = pkg in Prefs.limits(this)
        var limitMode by remember { mutableStateOf(false) }
        var min by remember { mutableStateOf("30") }
        AlertDialog(
            onDismissRequest = { quickPkg = null },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (limitMode) {
                        OutlinedTextField(
                            min, { min = it.filter(Char::isDigit).take(4) }, label = { Text("Minuti al giorno") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                        )
                        Text("Dopo il salvataggio NON si potrà più modificare.", color = MaterialTheme.colorScheme.error)
                    } else {
                        if (!isBlocked) Button(onClick = {
                            Prefs.setBlocked(this@MainActivity, Prefs.blocked(this@MainActivity) + pkg)
                            Policy.apply(this@MainActivity); quickPkg = null; refresh++
                        }, Modifier.fillMaxWidth()) { Text("🔒 Blocca sempre") }
                        else Text("È già bloccata.")
                        if (!hasLimit) OutlinedButton(onClick = { limitMode = true }, Modifier.fillMaxWidth()) {
                            Text("⏱ Imposta limite giornaliero")
                        } else Text("Ha già un limite (non modificabile).")
                    }
                }
            },
            confirmButton = {
                if (limitMode) TextButton(enabled = (min.toIntOrNull() ?: 0) in 1..1440, onClick = {
                    val m = min.toInt(); quickPkg = null
                    withConfirm("Confermi?", "$label: $m minuti al giorno, per sempre.") {
                        Prefs.addLimit(this@MainActivity, pkg, m)
                        Limits.tick(this@MainActivity); Policy.apply(this@MainActivity)
                    }
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { quickPkg = null }) { Text("Chiudi") } }
        )
    }

    @Composable
    private fun PermissionsCard(rev: Int) {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }
        @Suppress("UNUSED_EXPRESSION") tick
        val checks = Checks.all(this)
        val bad = checks.count { it.ok == false }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (bad == 0) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (bad == 0) "Verifica autorizzazioni: tutto ok ✅" else "Verifica autorizzazioni: mancano $bad ❌",
                    fontWeight = FontWeight.Bold
                )
                checks.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when (c.ok) { true -> "✅"; false -> "❌"; null -> "❔" }, Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, fontWeight = FontWeight.Medium)
                            Text(c.why, style = MaterialTheme.typography.bodySmall)
                        }
                        if (c.ok != true && c.fix != null) TextButton(onClick = {
                            runCatching { startActivity(c.fix.invoke()) }.onFailure {
                                runCatching {
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:$packageName")))
                                }
                            }
                        }) { Text(if (c.ok == null) "Apri" else "Sistema") }
                    }
                }
            }
        }
    }

    @Composable
    private fun DiagnosticsCard(rev: Int) {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }
        @Suppress("UNUSED_EXPRESSION") tick
        val ctx = this
        fun ago(key: String): String {
            val t = Prefs.diag(ctx, key)?.toLongOrNull() ?: return "mai ❌"
            val s = (System.currentTimeMillis() - t) / 1000
            return if (s < 90) "$s s fa ✅" else if (s < 3600) "${s / 60} min fa ⚠️" else "${s / 3600} h fa ❌"
        }
        val limits = Prefs.limits(ctx)
        val used = Limits.usageToday(ctx, limits.keys)
        val over = Limits.suspendedToday(ctx)
        val lines = buildList {
            add("Versione: ${BuildConfig.VERSION_NAME}")
            add("Modalità: " + if (Policy.isOwner(ctx)) "Device Owner" else "base")
            add("Dati di utilizzo: " + if (Limits.hasUsageAccess(ctx)) "sì ✅" else "NO ❌")
            add("Accessibilità attivata: " + if (BlockAccessibilityService.isEnabled(ctx)) "sì ✅" else "NO ❌")
            add("Accessibilità collegata: " + ago("acc_conn"))
            add("Accessibilità ultimo controllo: " + ago("acc_loop"))
            add("Servizio limiti ultimo controllo: " + ago("guard_loop"))
            add("App in primo piano rilevata: " + (Prefs.diag(ctx, "fg")?.let { labelOf(it, null) } ?: "-"))
            limits.forEach { (p, m) ->
                add("• ${labelOf(p, null)}: ${Stats.fmt(used[p] ?: 0L)} / $m min" + if (p in over) " → BLOCCATA" else "")
            }
            Prefs.diag(ctx, "err")?.let { add("Ultimo errore: $it") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Diagnostica", fontWeight = FontWeight.Bold)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    @Composable
    private fun HeroCard(r: UsageReport, nBlocked: Int, nLimits: Int) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Tempo sullo schermo oggi", style = MaterialTheme.typography.labelLarge)
                Text(Stats.fmt(r.today), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                val diff = r.today - r.yesterday
                if (r.yesterday > 0) Text(
                    (if (diff <= 0) "▼ " else "▲ ") + Stats.fmt(kotlin.math.abs(diff)) +
                        (if (diff <= 0) " meno di ieri" else " più di ieri (finora)"),
                    color = if (diff <= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("${r.opensTotal}", "aperture")
                    Stat("$nBlocked", "bloccate")
                    Stat("$nLimits", "con limite")
                    val avg = r.dayTotals.dropLast(1).filter { it > 0 }.let { if (it.isEmpty()) 0L else it.sum() / it.size }
                    Stat(if (avg > 0) Stats.fmt(avg).replace(" min", "m").replace(" h ", "h ") else "-", "media/g")
                }
            }
        }
    }

    @Composable
    private fun RowScope.Stat(value: String, label: String) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }

    @Composable
    private fun ChartCard(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }

    @Composable
    private fun WeekChart(r: UsageReport) {
        val max = (r.dayTotals.maxOrNull() ?: 0L).coerceAtLeast(60_000L)
        val names = listOf("Dom", "Lun", "Mar", "Mer", "Gio", "Ven", "Sab")
        val barH = 120.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            r.dayTotals.forEachIndexed { i, ms ->
                val isToday = i == r.dayTotals.lastIndex
                val dow = java.util.Calendar.getInstance().apply { timeInMillis = r.dayStarts[i] }
                    .get(java.util.Calendar.DAY_OF_WEEK) - 1
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val h = ms / 3_600_000.0
                    Text(
                        if (ms < 60_000) "" else if (h >= 1) "%.1fh".format(h) else "${ms / 60_000}m",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1
                    )
                    Box(Modifier.height(barH).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight((ms.toFloat() / max).coerceIn(0.02f, 1f))
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(
                                    if (isToday) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                )
                        )
                    }
                    Text(
                        if (isToday) "Oggi" else names[dow], style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }

    @Composable
    private fun HourChart(hours: LongArray) {
        val max = (hours.maxOrNull() ?: 0L).coerceAtLeast(60_000L)
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        Column {
            Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                hours.forEachIndexed { h, ms ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight((ms.toFloat() / max).coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                when {
                                    h == now -> MaterialTheme.colorScheme.primary
                                    h > now -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                }
                            )
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("0", "6", "12", "18", "24").forEachIndexed { i, t ->
                    Text(
                        t, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        textAlign = when (i) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center }
                    )
                }
            }
            val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
            if (hours[peak] >= 60_000) Text(
                "Ora più intensa: ${peak}:00–${peak + 1}:00 (${Stats.fmt(hours[peak])})",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    @Composable
    private fun BasicBanner(rev: Int) {
        val on = BlockAccessibilityService.isEnabled(this)
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                if (on) Text("Modalità base attiva (Accessibilità).", style = MaterialTheme.typography.bodySmall)
                else {
                    Text("Attiva \"Blocco App\" in Impostazioni → Accessibilità, altrimenti i blocchi non funzionano.")
                    Text(
                        "Se è grigio: Impostazioni → App → Blocco App → ⋮ in alto → \"Consenti impostazioni con restrizioni\", poi riprova.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("Apri Accessibilità")
                    }
                }
            }
        }
    }

    @Composable
    private fun SearchBox(q: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            q, onChange, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            label = { Text("Cerca app") }, singleLine = true
        )
    }

    @Composable
    private fun AppRow(a: AppInfo, sub: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
        Row(
            Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            a.icon?.let { Image(it, null, Modifier.size(36.dp)) } ?: Spacer(Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(a.label)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall)
            }
            trailing()
        }
    }

    @Composable
    private fun BlockedTab(apps: List<AppInfo>, rev: Int) {
        val blocked = Prefs.blocked(this)
        var q by rememberSaveable { mutableStateOf("") }
        val shown = apps.filter { q.isBlank() || it.label.contains(q, true) }
            .sortedWith(compareByDescending<AppInfo> { it.pkg in blocked }.thenBy { it.label.lowercase() })
        Column {
            Text(
                "Le app attive qui non si aprono. Attivare è libero, togliere richiede la password.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall
            )
            SearchBox(q) { q = it }
            LazyColumn {
                items(shown, key = { it.pkg }) { a ->
                    val on = a.pkg in blocked
                    AppRow(a) {
                        Switch(checked = on, onCheckedChange = { want ->
                            if (want) {
                                Prefs.setBlocked(this@MainActivity, Prefs.blocked(this@MainActivity) + a.pkg)
                                val failed = Policy.apply(this@MainActivity)
                                if (a.pkg in failed) toast("Android non permette di bloccare ${a.label}")
                                refresh++
                            } else withPassword("Sbloccare ${a.label}?") {
                                Prefs.setBlocked(this@MainActivity, Prefs.blocked(this@MainActivity) - a.pkg)
                                Policy.apply(this@MainActivity)
                            }
                        })
                    }
                }
            }
        }
    }

    @Composable
    private fun LimitsTab(apps: List<AppInfo>, rev: Int) {
        val limits = Prefs.limits(this)
        val access = Limits.hasUsageAccess(this)
        val used = remember(rev) { Limits.usageToday(this, limits.keys) }
        val over = Limits.suspendedToday(this)
        var q by rememberSaveable { mutableStateOf("") }
        var editing by remember { mutableStateOf<AppInfo?>(null) }
        val shown = apps.filter { q.isBlank() || it.label.contains(q, true) }
            .sortedWith(compareByDescending<AppInfo> { it.pkg in limits }.thenBy { it.label.lowercase() })
        Column {
            Text(
                "Minuti al giorno per app. Il limite si imposta UNA volta e poi non si cambia più, neanche con la password. Si azzera a mezzanotte.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall
            )
            if (!access) {
                Card(
                    Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Serve \"Accesso ai dati di utilizzo\". Senza, le app con limite restano bloccate.")
                        Button(onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                            Text("Concedi accesso")
                        }
                    }
                }
            }
            SearchBox(q) { q = it }
            LazyColumn {
                items(shown, key = { it.pkg }) { a ->
                    val lim = limits[a.pkg]
                    val sub = lim?.let {
                        val m = ((used[a.pkg] ?: 0L) / 60000).toInt()
                        "Limite $it min · usati $m min" + if (a.pkg in over) " · FINITO per oggi" else ""
                    }
                    AppRow(a, sub, onClick = if (lim == null) ({ editing = a }) else null) {
                        if (lim == null) TextButton(onClick = { editing = a }) { Text("Imposta") }
                        else Text("🔒")
                    }
                }
            }
        }
        editing?.let { a ->
            var min by remember { mutableStateOf("30") }
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Limite per ${a.label}") },
                text = {
                    Column {
                        OutlinedTextField(
                            min, { min = it.filter(Char::isDigit).take(4) }, label = { Text("Minuti al giorno") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                        )
                        Text("Dopo il salvataggio NON si potrà più modificare.", color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(enabled = (min.toIntOrNull() ?: 0) in 1..1440, onClick = {
                        val m = min.toInt()
                        editing = null
                        withConfirm("Confermi?", "${a.label}: $m minuti al giorno, per sempre.") {
                            Prefs.addLimit(this@MainActivity, a.pkg, m)
                            Limits.tick(this@MainActivity)
                            Policy.apply(this@MainActivity)
                        }
                    }) { Text("Salva") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Annulla") } }
            )
        }
    }

    private val presets = listOf(
        "TikTok" to "com.zhiliaoapp.musically",
        "Instagram" to "com.instagram.android",
        "Facebook" to "com.facebook.katana",
        "YouTube" to "com.google.android.youtube",
        "Reddit" to "com.reddit.frontpage",
        "X / Twitter" to "com.twitter.android",
        "Snapchat" to "com.snapchat.android",
        "Twitch" to "tv.twitch.android.app",
    )

    @Composable
    private fun NeverTab(rev: Int) {
        val never = Prefs.neverInstall(this)
        var text by rememberSaveable { mutableStateOf("") }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    (if (Policy.isOwner(this@MainActivity)) "Se una di queste app viene installata, Blocco App la disinstalla subito. "
                    else "In modalità base queste app, se installate, non si aprono. ") +
                        "La lista si può SOLO allungare.\n\nDal Play Store: apri la pagina dell'app → Condividi → Blocco App.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item {
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth(),
                    label = { Text("Link Play Store o nome pacchetto") }, singleLine = true
                )
            }
            item {
                Button(onClick = {
                    val p = NeverInstall.parse(text)
                    if (p == null) toast("Non riconosciuto") else { sharedPkg = p; text = "" }
                }) { Text("Aggiungi") }
            }
            item { Text("Rapide", fontWeight = FontWeight.Bold) }
            items(presets.filter { it.second !in never }) { (name, pkg) ->
                OutlinedButton(onClick = { sharedPkg = pkg }, Modifier.fillMaxWidth()) { Text("+ $name") }
            }
            item { Text("In lista (${never.size})", fontWeight = FontWeight.Bold) }
            items(never.sorted()) { p ->
                val name = presets.firstOrNull { it.second == p }?.first
                Text("🚫 " + (name?.let { "$it  ($p)" } ?: p))
            }
        }
    }

    @Composable
    private fun OtherTab(rev: Int) {
        var changePw by remember { mutableStateOf(false) }
        if (changePw) {
            NewPasswordScreen(needOld = true) { changePw = false }
            return
        }
        val scope = rememberCoroutineScope()
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("Protezioni anti-aggiramento", fontWeight = FontWeight.Bold) }
            if (!Policy.isOwner(this@MainActivity)) item {
                Text("Disponibili solo da Device Owner.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { Prefs.setBasicMode(this@MainActivity, false); refresh++ }) {
                    Text("Passa a Device Owner")
                }
            }
            if (Policy.isOwner(this@MainActivity)) items(Protection.values().toList()) { p ->
                val on = Prefs.protection(this@MainActivity, p)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title)
                        Text(p.desc, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = on, onCheckedChange = { want ->
                        val act = { Prefs.setProtection(this@MainActivity, p, want); Policy.apply(this@MainActivity) }
                        if (want) { act(); refresh++ } else withPassword("Disattivare \"${p.title}\"?") { act() }
                    })
                }
            }
            item { HorizontalDivider() }
            item {
                OutlinedButton(onClick = { withPassword("Cambiare la password?") { changePw = true } }) {
                    Text("Cambia password")
                }
            }
            item {
                OutlinedButton(onClick = {
                    scope.launch {
                        val info = Updater.check()
                        if (info == null) toast("Sei all'ultima versione (${BuildConfig.VERSION_NAME})")
                        else doUpdate()
                    }
                }) { Text("Controlla aggiornamenti") }
            }
            item { HorizontalDivider() }
            item { PermissionsCard(rev) }
            item { DiagnosticsCard(rev) }
            item { HorizontalDivider() }
            item { Text("Uscita d'emergenza", fontWeight = FontWeight.Bold) }
            item {
                val req = Prefs.releaseRequestedAt(this@MainActivity)
                val left = req + RELEASE_DELAY - System.currentTimeMillis()
                when {
                    req == 0L -> Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            withPassword("Richiedere la disattivazione? Potrai confermarla solo tra 24 ore, con di nuovo la password.") {
                                Prefs.setReleaseRequestedAt(this@MainActivity, System.currentTimeMillis())
                            }
                        }
                    ) { Text("Richiedi disattivazione (attesa 24 h)") }
                    left > 0 -> Column {
                        Text("Disattivazione richiesta: confermabile tra ${left / 3_600_000} h ${(left / 60_000) % 60} min.")
                        OutlinedButton(onClick = { Prefs.setReleaseRequestedAt(this@MainActivity, 0L); refresh++ }) {
                            Text("Annulla richiesta")
                        }
                    }
                    else -> Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            withPassword("Togliere TUTTI i blocchi e permettere la disinstallazione di Blocco App?") {
                                Policy.releaseAll(this@MainActivity)
                                Prefs.setReleaseRequestedAt(this@MainActivity, 0L)
                                toast("Blocchi rimossi. Ora puoi disinstallare l'app.")
                            }
                        }
                    ) { Text("Disattiva tutto ora") }
                }
            }
            item {
                Text(
                    "Versione ${BuildConfig.VERSION_NAME} · aggiornamento automatico attivo",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    companion object {
        const val RELEASE_DELAY = 24 * 3_600_000L
        private val HIDDEN = setOf("com.android.settings")

        fun loadApps(ctx: Context): List<AppInfo> {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val ris = pm.queryIntentActivities(i, PackageManager.MATCH_DISABLED_COMPONENTS)
            val seen = HashSet<String>()
            val out = ArrayList<AppInfo>()
            for (r in ris) {
                val p = r.activityInfo.packageName
                if (p == ctx.packageName || p in HIDDEN || !seen.add(p)) continue
                val icon = runCatching { r.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                out += AppInfo(p, r.loadLabel(pm).toString(), icon)
            }
            // App bloccate/limitate non più visibili nel launcher: mostrale comunque.
            (Prefs.blocked(ctx) + Prefs.limits(ctx).keys).filter { it !in seen }.forEach {
                out += AppInfo(it, it, null)
            }
            return out.sortedBy { it.label.lowercase() }
        }
    }
}
