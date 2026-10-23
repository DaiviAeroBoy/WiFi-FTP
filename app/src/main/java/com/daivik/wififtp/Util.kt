package com.daivik.wififtp

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** Local IPv4 on Wi-Fi / hotspot / ethernet (never mobile data). */
fun localIp(): String? {
    val list = try { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() } catch (e: Exception) { emptyList() }
    val ok = Regex("^(wlan|ap|swlan|softap|eth|rndis|usb)\\d*.*")
    for (ni in list) {
        if (!ni.isUp || ni.isLoopback || !ok.matches(ni.name)) continue
        for (a in ni.inetAddresses) {
            if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
        }
    }
    return null
}

/** Converts a picked internal-storage / SD-card document or tree Uri into a real path. */
fun uriToFile(uri: Uri, isTree: Boolean): File? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = try {
        if (isTree) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(uri)
    } catch (e: Exception) { return null }
    val parts = docId.split(":", limit = 2)
    val vol = parts[0]
    val rel = parts.getOrElse(1) { "" }
    val base = if (vol == "primary") Environment.getExternalStorageDirectory() else File("/storage/$vol")
    return if (rel.isEmpty()) base else File(base, rel)
}

fun hasIp(ip: String): Boolean {
    val list = try { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() } catch (e: Exception) { return true }
    for (ni in list) {
        try { if (!ni.isUp) continue } catch (e: Exception) { continue }
        for (a in ni.inetAddresses) if (a.hostAddress == ip) return true
    }
    return false
}

fun fmtSpeed(b: Long): String = when {
    b >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB/s", b / 1_000_000.0)
    b >= 1_000 -> String.format(java.util.Locale.US, "%.0f KB/s", b / 1_000.0)
    else -> "$b B/s"
}

fun qrBitmap(text: String, size: Int = 512): android.graphics.Bitmap? = try {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(
        text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
    )
    val w = m.width
    val h = m.height
    val px = IntArray(w * h) { i -> if (m.get(i % w, i / w)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
} catch (e: Exception) { null }
