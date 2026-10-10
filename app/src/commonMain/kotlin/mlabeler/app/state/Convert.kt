package mlabeler.app.state

import mlabeler.app.i18n.L
import mlabeler.core.format.LabelFormat
import mlabeler.core.io.Item

// Turning the label files of the folder into another format.

/** Formats label files can be turned into. */
val convertTargets = listOf(LabelFormat.Lab, LabelFormat.TextGrid, LabelFormat.Audacity, LabelFormat.Ds)

/** In oto mode the conversion is only oto.ini into NiaoNiao marks, so it is offered when the folder has an oto.ini. */
fun EditorState.otoConvertible(): Boolean = niaoHasOto(niaoFiles())

/** Files with labels in a format other than [target] (rows of a transcriptions.csv stay where they are). */
fun EditorState.filesToConvert(target: LabelFormat): List<Item> =
    items.filter { it.labelPath != null && it.labelFormat != null && it.labelFormat != target && it.labelFormat != LabelFormat.DsCsv }

/**
 * Writes the labels of [files] in [target] next to the recordings; the old files go to .mlabeler/backup/converted-…
 * (so the folder has one label file per recording). Returns the number converted and the errors.
 */
fun EditorState.convertLabels(files: List<Item>, target: LabelFormat, makeDefault: Boolean): Pair<Int, List<String>> {
    finishEditing()
    if (labelsDirty) saveLabels(quiet = true)
    val folder = "converted-" + workspace.stamp()
    var done = 0
    val errors = mutableListOf<String>()
    for (f in files) {
        try {
            val oldPath = f.labelPath ?: continue
            val d = currentLabels(f) ?: continue
            val dur = if (f.id == item?.id) duration else d.end
            val updated = workspace.writeLabels(f.copy(labelPath = null, labelFormat = null), d, dur, target)
            if (updated.labelPath != oldPath) workspace.moveToBackup(oldPath, folder)
            if (f.id != item?.id) histories.remove(f.id)
            items = items.map { x -> if (x.id == f.id) updated else x }
            updated.labelPath?.let { p -> labelMtime[updated.id] = runCatching { workspace.fs.lastModified(p) }.getOrDefault(0L) }
            done++
        } catch (e: Exception) {
            errors += f.name + ": " + (e.message ?: e.toString())
        }
    }
    if (makeDefault) workspace.updateState { it.copy(defaultFormat = target) }
    labelsChangedOnDisk()
    app.message(if (errors.isEmpty()) convertedT.format(done, target.title) else convertedT.format(done, target.title) + "\n" + errors.joinToString("\n"), error = errors.isNotEmpty())
    return done to errors
}

private val convertedT = L("Converted {0} label files to {1}; the old ones are in .mlabeler/backup", "Переведено файлов разметки в {1}: {0}; старые лежат в .mlabeler/backup")
