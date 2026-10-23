package mlabeler.app.state

import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonPrimitive
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.core.audio.Wav
import mlabeler.core.io.Paths

/** A/B changes only which marker values are rendered. It never writes audio or oto.ini. */
fun EditorState.playOtoTransition(original: Boolean = false) {
    val pinned = oto.transitionFirst ?: return
    val current = oto.current() ?: return
    val first = if (original) oto.original(pinned) ?: return else pinned
    val second = if (original) oto.original(current) ?: return else current
    val dir = item?.audioPath?.let { Paths.parent(it) } ?: return
    val settings = app.settings.otoNote
    if (settings.method == "resampler" && settings.resampler.isBlank()) {
        app.message(L("Choose a resampler first.", "Сначала выберите ресемплер.")(), error = true); return
    }
    toolkitJob?.cancel()
    describeWork("Singing transition", settings.method)
    toolkitJob = workScope.launch {
        val client = app.toolkit.client()
        var serverJob: String? = null
        try {
            beginToolkitWork(L("Preparing the transition", "Подготовка перехода")(), L("Singing two entries", "Прослушивание двух записей")())
            val tone = withContext(Dispatchers.Default) { otoNoteTone(current) }
            val paths = listOf(first, second).map { Paths.join(dir, it.sample) }
            val stamp = paths.map { workspace.fs.lastModified(it) }
            val key = "$dir|$first|$second|$settings|$tone|$stamp"
            val cached = oto.auditionCache[key]
            if (cached != null) { playBuffer(cached); return@launch }
            if (!app.toolkit.ensure()) error(app.toolkit.statusText())
            val ids = mutableMapOf<String, String>()
            for (path in paths.distinct()) {
                val wav = withContext(Dispatchers.Default) {
                    val bytes = workspace.fs.read(path)
                    if (Wav.isWav(bytes)) bytes else Wav.encode16(Platform.decodeAudio(path) ?: error("Unsupported audio"))
                }
                ids[path] = client.upload(Paths.name(path), wav)
            }
            serverJob = try {
                client.otoPair(ids.getValue(paths[0]), first, ids.getValue(paths[1]), second, tone,
                    settings.lengthMs.coerceIn(50, 10000), settings.method, settings.model, settings.resampler, settings.flags)
            } catch (e: mlabeler.app.toolkit.ToolkitException) {
                if (e.message in listOf("Not Found", "HTTP 404", "HTTP 405"))
                    error(L("Update mVocalToolkit to use transition playback.", "Обновите mVocalToolkit для прослушивания переходов.")())
                throw e
            }
            val result = client.await(serverJob, { toolkitDetail = it }) { p, stage -> toolkitProgress = p; toolkitBusy = stage }
            serverJob = null
            val path = result["file"]?.jsonPrimitive?.content ?: error("No audio result")
            val rendered = withContext(Dispatchers.Default) { Wav.decode(client.download(path)) }
            oto.auditionCache[key] = rendered
            while (oto.auditionCache.size > 2) oto.auditionCache.remove(oto.auditionCache.keys.first())
            playBuffer(rendered)
        } catch (e: CancellationException) {
            serverJob?.let { withContext(NonCancellable) { client.cancel(it) } }
            throw e
        } catch (e: Exception) {
            app.message(e.message ?: e.toString(), error = true)
        } finally { toolkitBusy = null; toolkitProgress = null }
    }
}
