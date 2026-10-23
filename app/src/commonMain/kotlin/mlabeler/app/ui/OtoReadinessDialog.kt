package mlabeler.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.io.Paths
import mlabeler.core.oto.*

@Composable
fun OtoReadinessDialog(ed: EditorState, close: () -> Unit) {
    val c = T.c
    val dir = ed.item?.audioPath?.let { Paths.parent(it) } ?: return
    var style by remember { mutableStateOf(RecStyle.CVVC) }
    var phonemizer by remember { mutableStateOf(OtoReadiness.Phonemizer.FullCoverage) }
    var prefix by remember { mutableStateOf(ed.app.settings.autoOto.aliasPrefix) }
    var suffix by remember { mutableStateOf(ed.app.settings.autoOto.aliasSuffix) }
    // An explicit inventory makes the check useful even for numbered filenames and incomplete banks.
    var inventory by remember { mutableStateOf("a i u e o ka ki ku ke ko sa shi su se so ta chi tsu te to na ni nu ne no ha hi fu he ho ma mi mu me mo ya yu yo ra ri ru re ro wa wo n") }
    var missingOnly by remember { mutableStateOf(false) }
    val files = remember(ed.items, dir) { ed.items.filter { Paths.parent(it.audioPath) == dir }.map { Paths.name(it.audioPath) }.toSet() }
    val issues = remember(ed.oto.version, style, prefix, suffix, inventory, files, phonemizer) {
        OtoReadiness.check(ed.oto.entries, inventory.split(Regex("\\s+")).filter { it.isNotBlank() }.flatMap { Syllables.fromName(it) }, style, files, prefix, suffix, phonemizer)
    }
    val shown = issues.filter { !missingOnly || it.kind == OtoReadiness.Kind.MissingAlias }
    Overlay(close, 760) {
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
          item { Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
            Text(L("Check Japanese alias coverage", "Проверить покрытие японских алиасов")(), color = c.text, fontSize = 17.sp)
            Text(L("For Japanese voicebanks only; Russian and other language banks are not supported by this check. Choose the alias convention and required syllables. This checks names and basic timings; it does not run an OpenUtau phonemizer or judge the sound.",
                "Проверка только для японских войсбанков; русские банки и банки других языков она не проверяет. Выберите схему алиасов и нужные слоги. Проверяются имена и базовые параметры; фонемайзер OpenUtau не запускается, звучание не оценивается.")(), color = c.muted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(L("Full coverage", "Полное покрытие")(), phonemizer == OtoReadiness.Phonemizer.FullCoverage) { phonemizer = OtoReadiness.Phonemizer.FullCoverage }
                Chip(L("OpenUtau: Japanese VCV (legacy)", "OpenUtau: японский VCV (legacy)")(), phonemizer == OtoReadiness.Phonemizer.OpenUtauJaVcv) { phonemizer = OtoReadiness.Phonemizer.OpenUtauJaVcv }
                Chip(L("OpenUtau: Japanese CVVC (legacy)", "OpenUtau: японский CVVC (legacy)")(), phonemizer == OtoReadiness.Phonemizer.OpenUtauJaCvvc) { phonemizer = OtoReadiness.Phonemizer.OpenUtauJaCvvc }
            }
            Text(L("OpenUtau profiles check preferred and fallback aliases in one prefix/suffix group; prefix.map, voice colours and presamp.ini are not evaluated.",
                "Профили OpenUtau проверяют основные и запасные алиасы в одной группе префикса/суффикса; prefix.map, цвета голоса и presamp.ini не учитываются.")(), color = c.muted, fontSize = 11.sp)
            if (phonemizer == OtoReadiness.Phonemizer.FullCoverage) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in listOf(RecStyle.CV, RecStyle.VCV, RecStyle.CVVC)) Chip(s.name, style == s) { style = s }
            }
            Text(L("Required syllables (space separated)", "Нужные слоги через пробел")(), color = c.text, fontSize = 12.sp)
            Field(inventory, { inventory = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(prefix, { prefix = it }, Modifier.weight(1f), placeholder = L("Prefix", "Префикс")())
                Field(suffix, { suffix = it }, Modifier.weight(1f), placeholder = L("Pitch/colour suffix", "Суффикс высоты/тембра")())
            }
            Text(L("Issues: {0}", "Замечаний: {0}").format(issues.size), color = c.text, fontSize = 13.sp)
            Chip(L("Only missing aliases", "Только недостающие алиасы")(), missingOnly) { missingOnly = !missingOnly }
          } }
            items(shown) { issue ->
                val reason = when (issue.kind) {
                    OtoReadiness.Kind.MissingAlias -> L("Missing", "Отсутствует")()
                    OtoReadiness.Kind.FallbackAlias -> L("Fallback instead", "Запасной вместо")()
                    OtoReadiness.Kind.DuplicateAlias -> L("Duplicate alias", "Повтор алиаса")()
                    OtoReadiness.Kind.MissingSample -> L("Missing recording", "Нет записи")()
                    OtoReadiness.Kind.InvalidTiming -> L("Invalid timing", "Некорректные параметры")()
                }
                Text("$reason: ${issue.alias}" + issue.sample?.let { " [$it]" }.orEmpty(), color = c.warn, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
            }
            if (issues.isEmpty()) item { Text(L("No issues for this inventory and convention.", "Для выбранных слогов и схемы замечаний нет.")(), color = c.text) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            Btn(L("Close", "Закрыть")(), onClick = close)
        }
    }
}
