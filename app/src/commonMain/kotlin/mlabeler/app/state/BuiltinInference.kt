package mlabeler.app.state

import mlabeler.core.audio.Audio
import mlabeler.core.mcfl.Mcfl

/** Browser implementation runs the same model off the UI thread. */
expect suspend fun inferBuiltin(audio: Audio, language: String, known: List<String>, threads: Int, confidence: Boolean, progress: (Double) -> Unit): List<Mcfl.Segment>
