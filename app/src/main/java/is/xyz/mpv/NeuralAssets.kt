package `is`.xyz.mpv

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bundled, pinned source; never downloads models or reads back video pixels. */
internal object NeuralAssets {
    private val mutex = Mutex()
    private val installed = mutableMapOf<Int, File>()
    suspend fun install(context: Context, model: NeuralModel): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            installed[model.channels]?.takeIf { it.isFile }?.let { return@withLock it }
            val bytes = context.assets.open("neural/" + model.file).use { it.readBytes() }
            check(model.verifies(bytes)) { "bundled_model_integrity" }
            val directory = File(context.filesDir, "owl-neural").apply { check(isDirectory || mkdirs()) }
            val output = File(directory, model.file)
            if (!output.isFile || !model.verifies(output.readBytes())) {
                val temporary = File(directory, model.file + ".tmp")
                temporary.writeBytes(bytes)
                check(temporary.renameTo(output)) { "model_install_failed" }
            }
            installed[model.channels] = output
            output
        }
    }
}
