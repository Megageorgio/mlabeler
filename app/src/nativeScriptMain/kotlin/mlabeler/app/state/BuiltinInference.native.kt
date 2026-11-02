package mlabeler.app.state

import mlabeler.core.audio.Audio
import mlabeler.core.mcfl.Mcfl

actual suspend fun inferBuiltin(audio: Audio, language: String, known: List<String>, threads: Int, confidence: Boolean, progress: (Double) -> Unit): List<Mcfl.Segment> {
    val model = QuickLabel.model() ?: error(QuickLabel.noModel())
    return if (known.isEmpty()) model.label(audio, language, threads = threads, confidence = confidence, progress = progress)
        else model.align(audio, language, known, threads, confidence, progress)
}
