package io.rewynd.worker

import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.store.Directory
import org.apache.lucene.store.IOContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun List<String>.execToString() = Runtime
    .getRuntime()
    .exec(this.toTypedArray())
    .let {
        if (it.waitFor(5, TimeUnit.SECONDS)) {
            log.error { it.errorReader().readText() }
            val text = it.inputReader().readText()
            log.info { text }
            text
        } else {
            it.destroy()
            throw TimeoutException("Command failed to execute in alloted time: ${this.joinToString(" ")}")
        }
    }


fun Directory.serialize(): ByteArray = ByteArrayOutputStream().let { bos ->
    ZipOutputStream(bos).let { writer ->
        listAll().map { fileName ->
            openInput(fileName, IOContext.READONCE).use {
                val arr = ByteArray(it.length().toInt())
                val entry = ZipEntry(fileName)
                it.readBytes(arr, 0, it.length().toInt())
                writer.putNextEntry(entry)
                writer.write(arr)
                writer.closeEntry()
            }
        }
    }
    bos.toByteArray()
}

fun deserializeDirectory(byteArray: ByteArray) = ZipInputStream(byteArray.inputStream()).use { zip ->
    val directory = ByteBuffersDirectory()
    do {
        val entry = zip.nextEntry
        if (entry != null) {
            directory.createOutput(entry.name, IOContext.READONCE).use {
                val bytes = zip.readAllBytes()
                it.writeBytes(bytes, bytes.size)
            }
        }
    } while (entry != null)
    directory
}