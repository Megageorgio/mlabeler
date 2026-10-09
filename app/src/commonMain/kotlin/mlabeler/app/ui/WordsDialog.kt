package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.theme.T
import mlabeler.core.io.Item
import mlabeler.core.io.Paths

/**
 * Words of an aligner model to look at: those of [text] or of the .txt files next to [files] that the model's
 * dictionary lacks, and the model's own words.
 */
data class WordsCheck(val model: String, val language: String?, val text: String = "", val files: List<Item> = emptyList())

private val titleT = L("Words of the model", "Слова модели")
private val aboutT = L(
    "The aligner turns words into phonemes with its dictionary. Words it lacks are guessed by a G2P model of the language when there is one; otherwise the file fails. Write the phonemes of such words here: they are kept in the toolkit as the model's own words and used every time.",
    "Выравниватель превращает слова в фонемы по своему словарю. Слова, которых в нём нет, угадывает G2P-модель языка, если она есть; иначе файл не размечается. Впишите здесь фонемы таких слов: они сохранятся в тулките как собственные слова модели и будут использоваться всегда.",
)
private val checkingT = L("Checking the words…", "Проверка слов…")
private val unknownT = L("Not in the dictionary", "Нет в словаре")
private val allKnownT = L("Every word is in the dictionary or can be guessed", "Все слова есть в словаре или угадываются")
private val guessT = L("guessed by G2P", "угадано G2P")
private val noGuessT = L("no guess: write the phonemes", "не угадано: впишите фонемы")
private val ownT = L("Own words ({0})", "Собственные слова ({0})")
private val noOwnT = L("None yet", "Пока нет")
private val wordT = L("word", "слово")
private val phonemesT = L("phonemes, e.g. k a sh a", "фонемы, например k a sh a")
private val addT = L("Add", "Добавить")
private val skipT = L("When a word can't be spelled, leave it out instead of skipping the file", "Если слово не удаётся записать фонемами, пропускать его, а не весь файл")
private val savedT = L("Own words saved: {0}", "Собственных слов сохранено: {0}")
private val filesT = L("Words of {0} files", "Слова из файлов: {0}")

@Composable
fun WordsDialog(app: AppState, check: WordsCheck) {
    val c = T.c
    val scope = rememberCoroutineScope()
    fun close() { app.wordsCheck = null }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // word to its phonemes as typed
    val unknown = remember { mutableStateListOf<Pair<String, String>>() }
    val guessed = remember { mutableStateOf(emptySet<String>()) }
    val own = remember { mutableStateListOf<Pair<String, String>>() }
    var newWord by remember { mutableStateOf("") }
    var newPh by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(check) {
        try {
            if (!app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(app.toolkit.statusText())
            val client = app.toolkit.client()
            own.clear()
            own.addAll(client.words(check.model).entries.sortedBy { it.key }.map { it.key to it.value.joinToString(" ") })
            val ws = app.editor?.workspace
            val texts = if (check.files.isEmpty()) listOf(check.text).filter { it.isNotBlank() } else withContext(Dispatchers.Default) {
                check.files.mapNotNull { f ->
                    val p = Paths.join(Paths.parent(f.audioPath), Paths.stem(f.audioPath) + ".txt")
                    runCatching { ws?.fs?.read(p)?.decodeToString() }.getOrNull()?.let { mlabeler.core.format.TextImport.clean(it, false) }
                }.filter { it.isNotBlank() }
            }
            if (texts.isNotEmpty()) {
                val res = client.phonemize(texts, check.model, check.language, check = true)
                val g = res.flatMap { it.guessed.entries }.associate { it.key to it.value }
                val words = res.flatMap { it.unknown }.distinct().filter { w -> own.none { it.first == w } }
                unknown.clear()
                unknown.addAll(words.map { it to g[it].orEmpty().joinToString(" ") })
                guessed.value = g.filterValues { it.isNotEmpty() }.keys
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        loading = false
    }
    Overlay({ close() }, 600) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            Btn(S.save(), primary = true, enabled = !loading && !saving && error == null) {
                saving = true
                scope.launch {
                    try {
                        val words = LinkedHashMap<String, List<String>>()
                        for ((w, p) in own + unknown) {
                            val ph = p.split(Regex("\\s+")).filter { it.isNotEmpty() }
                            if (w.isNotBlank() && ph.isNotEmpty()) words[w.trim()] = ph
                        }
                        val saved = app.toolkit.client().setWords(check.model, words)
                        app.message(savedT.format(saved.size))
                        close()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        app.message(e.message ?: e.toString(), error = true)
                    }
                    saving = false
                }
            }
        }) {
            Text(titleT() + " · " + check.model, color = c.text, fontSize = 17.sp)
            Text(aboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (check.files.isNotEmpty()) Text(filesT.format(check.files.size), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            when {
                error != null -> Text(error!!, color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                loading -> Text(checkingT(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                else -> {
                    if (check.text.isNotBlank() || check.files.isNotEmpty()) {
                        SectionTitle(unknownT())
                        if (unknown.isEmpty()) Text(allKnownT(), color = c.muted, fontSize = 13.sp)
                        Column(Modifier.heightIn(max = 240.dp).scrollWithHint()) {
                            for ((i, row) in unknown.withIndex()) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.width(150.dp)) {
                                        Text(row.first, color = c.text, fontSize = 14.sp)
                                        Text(if (row.first in guessed.value) guessT() else noGuessT(), color = if (row.first in guessed.value) c.muted else c.danger, fontSize = 10.sp)
                                    }
                                    Field(row.second, { v -> unknown[i] = row.first to v }, Modifier.weight(1f), placeholder = phonemesT())
                                }
                            }
                        }
                    }
                    SectionTitle(ownT.format(own.size))
                    if (own.isEmpty()) Text(noOwnT(), color = c.muted, fontSize = 13.sp)
                    Column(Modifier.heightIn(max = 220.dp).scrollWithHint()) {
                        for ((i, row) in own.withIndex()) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(row.first, color = c.text, fontSize = 14.sp, modifier = Modifier.width(150.dp))
                                Field(row.second, { v -> own[i] = row.first to v }, Modifier.weight(1f), placeholder = phonemesT())
                                Text("✕", color = c.muted, fontSize = 14.sp, modifier = Modifier.clickable { own.removeAt(i) }.padding(6.dp))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(newWord, { newWord = it }, Modifier.width(150.dp), placeholder = wordT())
                        Field(newPh, { newPh = it }, Modifier.weight(1f), placeholder = phonemesT())
                        Btn(addT(), enabled = newWord.isNotBlank() && newPh.isNotBlank()) {
                            own.removeAll { it.first == newWord.trim() }
                            own.add(newWord.trim() to newPh.trim())
                            newWord = ""; newPh = ""
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).background(c.panelAlt).clickable {
                app.update { it.copy(toolkit = it.toolkit.copy(skipUnknownWords = !it.toolkit.skipUnknownWords)) }
            }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(skipT(), color = c.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Toggle(app.settings.toolkit.skipUnknownWords, { v -> app.update { it.copy(toolkit = it.toolkit.copy(skipUnknownWords = v)) } })
            }
        }
    }
}
