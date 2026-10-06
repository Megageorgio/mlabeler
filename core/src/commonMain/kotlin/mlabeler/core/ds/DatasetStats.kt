package mlabeler.core.ds

import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import mlabeler.core.model.NoteTier
import kotlin.math.roundToInt

/** What a set of labelled recordings contains, for judging whether it is enough to train on. */
data class DatasetStats(
    val files: Int,
    /** Total length of the labelled recordings, s. */
    val seconds: Double,
    /** Without pauses, s. */
    val singingSeconds: Double,
    val phonemes: List<PhonemeStat>,
    /** Notes by MIDI number (rounded): count and total length. */
    val notes: Map<Int, NoteStat>,
    /** In the labels but not in the dictionary, and in the dictionary but never used (empty without a dictionary). */
    val notInDictionary: List<String> = emptyList(),
    val unusedFromDictionary: List<String> = emptyList(),
) {
    data class PhonemeStat(val name: String, val count: Int, val seconds: Double, val files: Int) {
        val meanMs: Double get() = if (count == 0) 0.0 else seconds / count * 1000
    }

    data class NoteStat(val count: Int, val seconds: Double)

    fun rare(atMost: Int): List<PhonemeStat> = phonemes.filter { it.count <= atMost }

    companion object {
        /**
         * [docs]: the labels of each file; [pauses] are counted but not as singing; [dict] (when it lists phonemes)
         * is compared with what the labels use.
         */
        fun of(docs: List<LabelDoc>, pauses: Set<String>, dict: PhonemeDict? = null): DatasetStats {
            val count = mutableMapOf<String, Int>()
            val dur = mutableMapOf<String, Double>()
            val inFiles = mutableMapOf<String, Int>()
            val notes = mutableMapOf<Int, NoteStat>()
            var seconds = 0.0
            var singing = 0.0
            for (doc in docs) {
                val t = doc.tiers.getOrNull(doc.phonemeTierIndex()) as? IntervalTier ?: continue
                if (t.size == 0) continue
                seconds += t.bounds.last() - t.bounds.first()
                val seen = mutableSetOf<String>()
                for (i in 0 until t.size) {
                    val p = t.texts[i].trim()
                    if (p.isEmpty()) continue
                    val d = t.durationOf(i)
                    count[p] = (count[p] ?: 0) + 1
                    dur[p] = (dur[p] ?: 0.0) + d
                    if (p !in pauses) singing += d
                    if (seen.add(p)) inFiles[p] = (inFiles[p] ?: 0) + 1
                }
                for (nt in doc.tiers.filterIsInstance<NoteTier>()) for (n in nt.notes) {
                    val m = n.pitch?.roundToInt() ?: continue
                    val old = notes[m] ?: NoteStat(0, 0.0)
                    notes[m] = NoteStat(old.count + 1, old.seconds + (n.end - n.start))
                }
            }
            val stats = count.keys.map { PhonemeStat(it, count.getValue(it), dur.getValue(it), inFiles[it] ?: 0) }
                .sortedWith(compareByDescending<PhonemeStat> { it.count }.thenBy { it.name })
            val known = dict?.let { it.vowels + it.consonants + it.semivowels + it.special }.orEmpty()
            val used = count.keys.filter { it !in pauses }
            fun bare(p: String) = p.substringAfterLast('/')
            val notIn = if (known.isEmpty()) emptyList() else used.filter { it !in known && bare(it) !in known }.sorted()
            val unused = if (known.isEmpty()) emptyList() else known.filter { k -> used.none { it == k || bare(it) == k } }.distinct().sorted()
            return DatasetStats(docs.size, seconds, singing, stats, notes.entries.sortedBy { it.key }.associate { it.key to it.value }, notIn, unused)
        }
    }
}
