package com.daivik.wififtp

import java.io.File

/**
 * FTP root "/" lists the shared mounts (e.g. Internal, SDCard, or the one chosen folder/file).
 * Nothing outside the mounts is reachable.
 */
class VirtualFs(private val mounts: Map<String, File>) {

    fun rootEntries(): List<Pair<String, File>> = mounts.toList()

    private fun segs(v: String) = v.split("/").filter { it.isNotEmpty() }

    fun normalize(cwd: String, arg: String): String {
        val full = if (arg.startsWith("/")) arg else "$cwd/$arg"
        val out = ArrayList<String>()
        for (p in full.split("/")) {
            when (p) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(p)
            }
        }
        return "/" + out.joinToString("/")
    }

    /** null = virtual root or unknown mount. */
    fun resolve(v: String): File? {
        val s = segs(v)
        if (s.isEmpty()) return null
        var f = mounts[s[0]] ?: return null
        for (x in s.drop(1)) f = File(f, x)
        return f
    }

    /** Top-level entries (the mounts themselves) can't be deleted/renamed/overwritten. */
    fun isMount(v: String) = segs(v).size <= 1
}
