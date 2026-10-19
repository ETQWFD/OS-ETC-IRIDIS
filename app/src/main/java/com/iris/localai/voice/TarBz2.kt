package com.iris.localai.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Unpacks sherpa-onnx release tarballs (.tar.bz2) into a pack directory. */
internal object TarBz2 {
    suspend fun extract(archive: File, into: File, strip: String, skip: List<String>) {
        val root = into.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val e = tar.nextEntry ?: break
                val rel = relativePath(e.name, strip) ?: continue
                if (rel.isEmpty() || skip.any { rel.startsWith(it) }) continue
                val out = File(root, rel).canonicalFile
                // Never write outside the pack directory ("../" entries).
                if (!out.path.startsWith(root.path + File.separator)) throw IOException("压缩包路径非法：${e.name}")
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isFile -> {
                        out.parentFile?.mkdirs()
                        val tmp = File(out.path + ".tmp")
                        tmp.outputStream().use { tar.copyTo(it, 1 shl 16) }
                        if (!tmp.renameTo(out)) { out.delete(); if (!tmp.renameTo(out)) throw IOException("无法写入 ${out.name}") }
                    }
                    else -> Unit // links / devices: not used by these archives
                }
            }
        }
    }

    /** "dir/a/b" with strip "dir/" → "a/b"; entries outside the stripped prefix are ignored (null). */
    fun relativePath(name: String, strip: String): String? {
        val n = name.removePrefix("./")
        if (strip.isEmpty()) return n
        return if (n.startsWith(strip)) n.substring(strip.length) else null
    }
}

internal object Sha256 {
    fun of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
