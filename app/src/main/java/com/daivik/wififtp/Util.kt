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
