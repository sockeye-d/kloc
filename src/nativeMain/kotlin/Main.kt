package dev.fishies.kloc

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.produce
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.exit
import platform.posix.sysconf
import kotlin.time.measureTimedValue

val NUL_BYTE = 0.toUByte()
val TAB_BYTE = 9.toUByte()
val LF_BYTE = 10.toUByte()
val CR_BYTE = 13.toUByte()
const val FILE_IS_BINARY = -1

data class ScanningResult(
    val scannedFiles: Int,
    val totalLines: Int,
)

data class FileToScan(
    val source: RawSource,
    val path: Path,
    val metadata: FileMetadata,
)

class Kloc : CliktCommand(name = "kloc") {
    val directory by argument().default(".")

    override fun run() {
        try {
            val (value, duration) = measureTimedValue {
                runBlocking {
                    countLinesInDirectory(directory)
                }
            }

            echo("Total: ${value.totalLines} lines")
            echo("Counted ${value.scannedFiles} files in $duration")
        } catch (_: FileNotFoundException) {
            echo("$directory does not exist", err = true)
            exit(1)
        }
    }

    override fun help(context: Context): String {
        return context.theme.info("kloc is a line counter written in Kotlin/Native.")
    }
}

fun main(args: Array<String>) = Kloc().main(args)

fun countLines(source: Source): Int {
    var length = 0
    var lines = 0
    var odd = 0
    val byteArray = ByteArray(1 shl 14)
    var lastByteWasCr = false
    while (true) {
        val bytesRead = source.readAtMostTo(byteArray)
        for (i in 0..<bytesRead) {
            val byte = byteArray[i].toUByte()
            length++
            // CR gets counted, so make sure not to double-count CRLF line endings
            if (byte == LF_BYTE && !lastByteWasCr) lines++
            if (byte == CR_BYTE) lines++
            if (byte == NUL_BYTE) return FILE_IS_BINARY
            if (byte < 32.toUByte() && byte != TAB_BYTE && byte != LF_BYTE && byte != CR_BYTE) {
                odd++
            }
            lastByteWasCr = byte == CR_BYTE
        }
        if (bytesRead < byteArray.size) break
    }
    return if (odd.toFloat() / length.toFloat() < 0.3) lines else FILE_IS_BINARY
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun CoroutineScope.countLinesInDirectory(directory: String): ScanningResult {
    val procCount = sysconf(_SC_NPROCESSORS_ONLN).toInt()
    val directoryWalkerCount = 2
    println("Processors: $procCount")
    val filePath = Path(directory)
    val workerCount = (procCount - directoryWalkerCount).coerceAtLeast(1)
    val lineCountChannel = Channel<Int>(Channel.UNLIMITED)
    val files = produce(Dispatchers.Default.limitedParallelism(directoryWalkerCount)) {
        scanFiles(filePath)
        close()
    }

    launch(Dispatchers.IO.limitedParallelism(workerCount)) {
        (1..workerCount).map { _ ->
            launch {
                for ((source) in files) {
                    try {
                        val lines = countLines(source.buffered())
                        if (lines != FILE_IS_BINARY) {
                            lineCountChannel.send(lines)
                        }
                    } catch (_: IOException) {
                    }
                }
            }
        }.joinAll()
        lineCountChannel.close()
    }

    var totalLines = 0
    var fileCount = 0
    for (lineCount in lineCountChannel) {
        if (fileCount % 1000 == 0) {
            println("Scanned $fileCount files")
        }
        totalLines += lineCount
        fileCount++
    }
    return ScanningResult(fileCount, totalLines)
}

suspend fun ProducerScope<FileToScan>.scanFiles(basePath: Path) {
    coroutineScope {
        scanFilesInternal(basePath, onFileEmitted = ::send)
    }
}

suspend fun CoroutineScope.scanFilesInternal(
    basePath: Path,
    level: Int = 0,
    onFileEmitted: suspend (FileToScan) -> Unit,
) {
    val paths = try {
        SystemFileSystem.list(basePath)
    } catch (_: IOException) {
        emptyList()
    }
    for (child in paths) {
        if (child.name.startsWith(".")) continue
        val metadata = try {
            SystemFileSystem.metadataOrNull(child)
        } catch (_: Exception) {
            null
        } ?: continue
        if (metadata.isRegularFile) {
            if (metadata.size > 20_000_000) {
                //println("Skipping ${child.name} (${metadata.size / 1_000_000} megabytes)")
            } else if (filenameIsGenerated(child.name)) {
                //println("Skipping generated ${child.name}")
            } else {
                try {
                    onFileEmitted(FileToScan(SystemFileSystem.source(child), child, metadata))
                } catch(_: Exception) {}
            }
        } else {
            if (level > 1) {
                scanFilesInternal(child, level + 1, onFileEmitted)
            } else {
                launch {
                    scanFilesInternal(child, level + 1, onFileEmitted)
                }
            }
        }
    }
}

fun filenameIsGenerated(filename: String): Boolean {
    val filenameLength = filename.length
    for (i in 0..<(filenameLength - 8)) {
        if (i == 0 || filename[i - 1] == '.') {
            if (filename[i] == 'g' && (i + 1 >= filenameLength || filename[i + 1] == '.')) {
                return true
            }
            if (i + 3 < filenameLength && (filename[i + 3] == '.' || i + 2 == filenameLength) && filename[i] == 'g' && filename[i + 1] == 'e' && filename[i + 2] == 'n') {
                return true
            }
            if (i + 8 < filenameLength && (filename[i + 8] == '.' || i + 8 == filenameLength) && filename[i] == 'g' && filename[i + 1] == 'e' && filename[i + 2] == 'n' && filename[i + 3] == 'e' && filename[i + 4] == 'r' && filename[i + 5] == 'a' && filename[i + 6] == 't' && filename[i + 7] == 'e' && filename[i + 8] == 'd') {
                return true
            }
        }
    }
    return false
}
