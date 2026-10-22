package com.daivik.wififtp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File
import java.security.SecureRandom

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("wififtp", MODE_PRIVATE) }
    private var permTick by mutableIntStateOf(0)

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            permTick++
            askStorage()
        }

    private fun hasStorage() = Environment.isExternalStorageManager()
    private fun hasNotif() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        if (!hasNotif()) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else askStorage()
    }

    private fun askStorage() {
        if (hasStorage()) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onResume() {
        super.onResume()
        permTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) askPermissions()
        setContent { App() }
    }

    @Composable
    private fun App() {
        val sysDark = isSystemInDarkTheme()
        var dark by remember { mutableStateOf(if (prefs.contains("dark")) prefs.getBoolean("dark", sysDark) else sysDark) }
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Screen(dark) { dark = it; prefs.edit().putBoolean("dark", it).apply() }
            }
        }
    }

    private fun buildMounts(mode: Mode, picked: File?): Pair<ArrayList<String>, ArrayList<String>> {
        val names = arrayListOf<String>()
        val paths = arrayListOf<String>()
        val internal = Environment.getExternalStorageDirectory()
        if (mode == Mode.FULL) {
            names.add("Internal"); paths.add(internal.absolutePath)
            val sm = getSystemService(StorageManager::class.java)
            var n = 0
            for (v in sm.storageVolumes) {
                val d = v.directory
                if (v.isRemovable && v.state == Environment.MEDIA_MOUNTED && d != null) {
                    n++
                    names.add(if (n == 1) "SDCard" else "SDCard$n")
                    paths.add(d.absolutePath)
                }
            }
        } else {
            val f = picked!!
            names.add(if (f.absolutePath == internal.absolutePath) "Internal" else f.name.ifEmpty { "Storage" })
            paths.add(f.absolutePath)
        }
        return names to paths
    }

    @Composable
    private fun Screen(dark: Boolean, onDark: (Boolean) -> Unit) {
        val st by ServerState.state.collectAsState()
        val tick = permTick
        val ctx = LocalContext.current
        val ok = remember(tick) { hasStorage() && hasNotif() }
        var mode by remember { mutableStateOf(Mode.FULL) }
        var picked by remember { mutableStateOf<File?>(null) }
        var usePin by remember { mutableStateOf(prefs.getBoolean("pin", false)) }
        val bad = "Pick something from internal storage or the SD card"

        val treeL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val f = uriToFile(uri, true)
                if (f == null) Toast.makeText(ctx, bad, Toast.LENGTH_LONG).show() else picked = f
            }
        }
        val fileL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val f = uriToFile(uri, false)
                if (f == null) Toast.makeText(ctx, bad, Toast.LENGTH_LONG).show() else picked = f
            }
        }

        fun start() {
            if (!ok) { askPermissions(); return }
            if (mode != Mode.FULL && picked == null) {
                Toast.makeText(ctx, "Choose what to share first", Toast.LENGTH_SHORT).show(); return
            }
            val (names, paths) = buildMounts(mode, picked)
            val i = Intent(ctx, FtpService::class.java)
                .setAction(FtpService.ACTION_START)
                .putStringArrayListExtra("names", names)
                .putStringArrayListExtra("paths", paths)
            if (usePin) i.putExtra("pin", "%06d".format(SecureRandom().nextInt(1000000)))
            ContextCompat.startForegroundService(ctx, i)
        }

        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("WiFi FTP", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onDark(!dark) }) { Text(if (dark) "☀️" else "🌙", fontSize = 22.sp) }
            }
            Spacer(Modifier.height(16.dp))

            if (!st.running) {
                Text("Share", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == Mode.FULL, onClick = { mode = Mode.FULL; picked = null }, label = { Text("Full storage") })
                    FilterChip(selected = mode == Mode.FOLDER, onClick = { mode = Mode.FOLDER; picked = null }, label = { Text("Folder") })
                    FilterChip(selected = mode == Mode.FILE, onClick = { mode = Mode.FILE; picked = null }, label = { Text("File") })
                }
                Spacer(Modifier.height(16.dp))
                if (mode == Mode.FULL) {
                    Text("Internal storage + SD card", style = MaterialTheme.typography.bodyMedium)
                } else {
                    OutlinedButton(onClick = {
                        if (mode == Mode.FOLDER) treeL.launch(null) else fileL.launch(arrayOf("*/*"))
                    }) { Text(if (mode == Mode.FOLDER) "Choose folder" else "Choose file") }
                    Spacer(Modifier.height(8.dp))
                    Text(picked?.absolutePath ?: "Nothing selected", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Password protect", Modifier.weight(1f))
                    Switch(checked = usePin, onCheckedChange = { usePin = it; prefs.edit().putBoolean("pin", it).apply() })
                }
                st.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (!ok) {
                    Spacer(Modifier.height(12.dp))
                    Text("Allow all-files access and notifications to continue.", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("START", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text(if (st.paused) "Paused" else "Server running", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            st.url, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                ctx.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("ftp", st.url))
                                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Type this in your PC file explorer address bar", style = MaterialTheme.typography.bodySmall)
                        st.pin?.let {
                            Spacer(Modifier.height(10.dp))
                            Text("Password: $it   (any username)", fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("Devices connected: ${st.clients.size}", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    items(st.clients) { c ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(c.ip, fontWeight = FontWeight.Medium)
                            Text(if (c.host != c.ip) c.host else "Hostname unavailable", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { FtpService.send(ctx, if (st.paused) FtpService.ACTION_RESUME else FtpService.ACTION_PAUSE) },
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) { Text(if (st.paused) "Resume" else "Pause") }
                    Button(
                        onClick = { FtpService.send(ctx, FtpService.ACTION_STOP) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Disconnect") }
                }
            }
        }
    }
}
