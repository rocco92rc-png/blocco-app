package com.roccoapps.bloccoapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_EXPRESSION") refresh
                    Root()
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
        if (Policy.isOwner(this)) {
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
    private fun Root() {
        val ctx = this
        when {
            !Policy.isOwner(ctx) -> SetupScreen()
            !Password.isSet(ctx) -> NewPasswordScreen(needOld = false) { refresh++ }
            else -> MainScreen()
        }
    }

    @Composable
    private fun SetupScreen() {
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
                OutlinedButton(onClick = { refresh++ }) { Text("Ho fatto, ricontrolla") }
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
    private fun MainScreen() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
        LaunchedEffect(refresh) { apps = withContext(Dispatchers.IO) { loadApps(this@MainActivity) } }
        val tabs = listOf("Bloccate", "Limiti", "Mai installare", "Altro")
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Text(
                "Blocco App", Modifier.padding(start = 16.dp, top = 12.dp),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
            )
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            val list = apps
            if (list == null && tab < 2) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else when (tab) {
                0 -> BlockedTab(list!!)
                1 -> LimitsTab(list!!)
                2 -> NeverTab()
                else -> OtherTab()
            }
        }
        Dialogs()
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
    private fun BlockedTab(apps: List<AppInfo>) {
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
    private fun LimitsTab(apps: List<AppInfo>) {
        val limits = Prefs.limits(this)
        val access = Limits.hasUsageAccess(this)
        val used = remember(refresh) { Limits.usageToday(this, limits.keys) }
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
    private fun NeverTab() {
        val never = Prefs.neverInstall(this)
        var text by rememberSaveable { mutableStateOf("") }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "Se una di queste app viene installata, Blocco App la disinstalla subito. " +
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
    private fun OtherTab() {
        var changePw by remember { mutableStateOf(false) }
        if (changePw) {
            NewPasswordScreen(needOld = true) { changePw = false }
            return
        }
        val scope = rememberCoroutineScope()
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("Protezioni anti-aggiramento", fontWeight = FontWeight.Bold) }
            items(Protection.values().toList()) { p ->
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
