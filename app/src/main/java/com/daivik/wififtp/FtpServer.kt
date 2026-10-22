package com.daivik.wififtp

import kotlinx.coroutines.flow.update
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class FtpServer(private val port: Int, private val fs: VirtualFs, private val pin: String?) {

    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<Long, Session>()
    private val hosts = ConcurrentHashMap<String, String>()
    private val ids = AtomicLong()
    @Volatile private var running = false

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        running = true
        pool.execute {
            while (running) {
                try {
                    val s = ss.accept()
                    val sess = Session(ids.incrementAndGet(), s)
                    sessions[sess.id] = sess
                    publish()
                    pool.execute(sess)
                } catch (e: Exception) {
                    if (!running) break
                    try { Thread.sleep(50) } catch (x: InterruptedException) { break }
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) {}
        sessions.values.forEach { it.close() }
        sessions.clear()
        pool.shutdownNow()
        publish()
    }

    private fun publish() {
        val list = sessions.values.groupBy { it.ip }
            .map { (ip, l) -> ClientInfo(ip, hosts[ip] ?: ip, l.size) }
            .sortedBy { it.ip }
        ServerState.state.update { it.copy(clients = list) }
    }

    inner class Session(val id: Long, private val sock: Socket) : Runnable {
        val ip: String = sock.inetAddress.hostAddress ?: "unknown"
        private val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        private val out = BufferedOutputStream(sock.getOutputStream())
        private var cwd = "/"
        private var authed = pin == null
        private var pasv: ServerSocket? = null
        private var active: InetSocketAddress? = null
        private var rnfr: File? = null
        private var rest = 0L

        init {
            try {
                pool.execute {
                    hosts.getOrPut(ip) {
                        try { InetAddress.getByName(ip).hostName } catch (e: Exception) { ip }
                    }
                    publish()
                }
            } catch (e: Exception) {}
        }

        override fun run() {
            try {
                sock.soTimeout = 15 * 60 * 1000
                reply(220, "WiFi FTP ready")
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isBlank()) continue
                    val sp = line.indexOf(' ')
                    val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase(Locale.US)
                    val arg = if (sp < 0) "" else line.substring(sp + 1).trim()
                    if (!handle(cmd, arg)) break
                }
            } catch (e: Exception) {
            } finally {
                close()
                sessions.remove(id)
                publish()
            }
        }

        fun close() {
            try { sock.close() } catch (e: Exception) {}
            closePasv()
        }

        private fun closePasv() {
            try { pasv?.close() } catch (e: Exception) {}
            pasv = null
        }

        private fun reply(code: Int, msg: String) {
            out.write("$code $msg\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }

        private fun path(arg: String) = fs.normalize(cwd, arg)

        private fun openData(): Socket? {
            val p = pasv
            val a = active
            active = null
            return try {
                val s: Socket? = when {
                    p != null -> {
                        p.soTimeout = 15000
                        val c = p.accept()
                        closePasv()
                        if (c.inetAddress != sock.inetAddress) { c.close(); null } else c
                    }
                    a != null -> Socket().also { it.connect(a, 15000) }
                    else -> null
                }
                s?.apply {
                    tcpNoDelay = true
                    sendBufferSize = 1 shl 19
                    receiveBufferSize = 1 shl 19
                    soTimeout = 60000
                }
            } catch (e: IOException) {
                closePasv()
                null
            }
        }

        private fun listLine(name: String, f: File): String {
            val dir = f.isDirectory
            val recent = System.currentTimeMillis() - f.lastModified() < 180L * 24 * 3600 * 1000
            val fmt = SimpleDateFormat(if (recent) "MMM dd HH:mm" else "MMM dd  yyyy", Locale.US)
            val perm = if (dir) "drwxr-xr-x" else "-rw-r--r--"
            val size = if (dir) 4096L else f.length()
            return "$perm 1 ftp ftp $size ${fmt.format(Date(f.lastModified()))} $name\r\n"
        }

        private fun copy(ins: InputStream, os: OutputStream) {
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                os.write(buf, 0, n)
            }
            os.flush()
        }

        private fun handle(cmd: String, arg: String): Boolean {
            if (!authed && cmd !in setOf("USER", "PASS", "QUIT", "FEAT", "SYST", "OPTS", "NOOP")) {
                reply(530, "Please log in"); return true
            }
            when (cmd) {
                "USER" -> reply(331, "Password required")
                "PASS" -> if (pin == null || arg == pin) { authed = true; reply(230, "Logged in") } else reply(530, "Wrong password")
                "SYST" -> reply(215, "UNIX Type: L8")
                "FEAT" -> {
                    out.write("211-Features:\r\n UTF8\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n EPSV\r\n211 End\r\n".toByteArray())
                    out.flush()
                }
                "OPTS", "TYPE", "MODE", "STRU", "NOOP" -> reply(200, "OK")
                "QUIT" -> { reply(221, "Bye"); return false }
                "PWD", "XPWD" -> reply(257, "\"${cwd.replace("\"", "\"\"")}\" is current directory")
                "CWD", "XCWD" -> {
                    val p = path(arg)
                    if (p == "/" || fs.resolve(p)?.isDirectory == true) { cwd = p; reply(250, "OK") }
                    else reply(550, "No such directory")
                }
                "CDUP", "XCUP" -> { cwd = path(".."); reply(200, "OK") }
                "PASV" -> {
                    closePasv()
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.receiveBufferSize = 1 shl 19
                    ss.bind(InetSocketAddress(sock.localAddress, 0))
                    pasv = ss
                    val b = sock.localAddress.address
                    val pt = ss.localPort
                    reply(227, "Entering Passive Mode (${b[0].toInt() and 255},${b[1].toInt() and 255},${b[2].toInt() and 255},${b[3].toInt() and 255},${pt shr 8},${pt and 255})")
                }
                "EPSV" -> {
                    closePasv()
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.receiveBufferSize = 1 shl 19
                    ss.bind(InetSocketAddress(sock.localAddress, 0))
                    pasv = ss
                    reply(229, "Entering Extended Passive Mode (|||${ss.localPort}|)")
                }
                "PORT" -> {
                    val n = arg.split(",").mapNotNull { it.trim().toIntOrNull() }
                    if (n.size != 6) { reply(501, "Bad PORT"); return true }
                    val addr = InetAddress.getByName("${n[0]}.${n[1]}.${n[2]}.${n[3]}")
                    if (addr != sock.inetAddress) { reply(501, "Address mismatch"); return true }
                    closePasv()
                    active = InetSocketAddress(addr, n[4] * 256 + n[5])
                    reply(200, "PORT OK")
                }
                "LIST", "NLST" -> {
                    val a = arg.split(" ").filter { !it.startsWith("-") }.joinToString(" ")
                    val p = path(if (a.isEmpty()) "." else a)
                    val entries: List<Pair<String, File>>? =
                        if (p == "/") fs.rootEntries()
                        else fs.resolve(p)?.let { f ->
                            if (f.isDirectory) f.listFiles()?.sortedBy { it.name.lowercase() }?.map { it.name to it }
                            else if (f.exists()) listOf(f.name to f) else null
                        }
                    if (entries == null) { reply(550, "Not found"); return true }
                    reply(150, "Listing")
                    val d = openData() ?: run { reply(425, "Can't open data connection"); return true }
                    try {
                        d.getOutputStream().use { os ->
                            for ((n, f) in entries) {
                                os.write((if (cmd == "NLST") "$n\r\n" else listLine(n, f)).toByteArray(Charsets.UTF_8))
                            }
                        }
                        reply(226, "Done")
                    } catch (e: IOException) { reply(426, "Aborted"); try { d.close() } catch (x: Exception) {} }
                }
                "SIZE" -> {
                    val f = fs.resolve(path(arg))
                    if (f != null && f.isFile) reply(213, f.length().toString()) else reply(550, "No such file")
                }
                "MDTM" -> {
                    val f = fs.resolve(path(arg))
                    if (f != null && f.exists()) {
                        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        reply(213, fmt.format(Date(f.lastModified())))
                    } else reply(550, "No such file")
                }
                "REST" -> { rest = arg.toLongOrNull() ?: 0L; reply(350, "Restarting at $rest") }
                "RETR" -> {
                    val f = fs.resolve(path(arg))
                    if (f == null || !f.isFile) { reply(550, "File not found"); return true }
                    reply(150, "Sending")
                    val d = openData() ?: run { reply(425, "Can't open data connection"); return true }
                    try {
                        FileInputStream(f).use { ins ->
                            if (rest > 0) ins.channel.position(rest)
                            d.getOutputStream().use { os -> copy(ins, os) }
                        }
                        reply(226, "Transfer complete")
                    } catch (e: IOException) { reply(426, "Transfer aborted"); try { d.close() } catch (x: Exception) {} }
                    rest = 0
                }
                "STOR", "APPE" -> {
                    val p = path(arg)
                    val f = fs.resolve(p)
                    if (f == null || fs.isMount(p) || f.isDirectory || f.parentFile?.isDirectory != true) {
                        reply(550, "Can't write there"); return true
                    }
                    reply(150, "Receiving")
                    val d = openData() ?: run { reply(425, "Can't open data connection"); return true }
                    try {
                        d.getInputStream().use { ins ->
                            FileOutputStream(f, cmd == "APPE").use { os -> copy(ins, os) }
                        }
                        reply(226, "Transfer complete")
                    } catch (e: IOException) { reply(426, "Transfer aborted"); try { d.close() } catch (x: Exception) {} }
                    rest = 0
                }
                "DELE" -> {
                    val p = path(arg); val f = fs.resolve(p)
                    if (f != null && !fs.isMount(p) && f.isFile && f.delete()) reply(250, "Deleted") else reply(550, "Can't delete")
                }
                "RMD", "XRMD" -> {
                    val p = path(arg); val f = fs.resolve(p)
                    if (f != null && !fs.isMount(p) && f.isDirectory && f.delete()) reply(250, "Removed") else reply(550, "Can't remove (folder must be empty)")
                }
                "MKD", "XMKD" -> {
                    val p = path(arg); val f = fs.resolve(p)
                    if (f != null && !fs.isMount(p) && f.mkdir()) reply(257, "\"$p\" created") else reply(550, "Can't create folder")
                }
                "RNFR" -> {
                    val p = path(arg); val f = fs.resolve(p)
                    if (f != null && !fs.isMount(p) && f.exists()) { rnfr = f; reply(350, "Ready for RNTO") } else reply(550, "Not found")
                }
                "RNTO" -> {
                    val p = path(arg); val t = fs.resolve(p); val s = rnfr
                    rnfr = null
                    if (s != null && t != null && !fs.isMount(p) && !t.exists() && s.renameTo(t)) reply(250, "Renamed") else reply(550, "Rename failed")
                }
                "ABOR" -> { closePasv(); reply(226, "OK") }
                else -> reply(502, "Not supported")
            }
            return true
        }
    }
}
