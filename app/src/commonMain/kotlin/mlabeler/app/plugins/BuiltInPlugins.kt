package mlabeler.app.plugins

import kotlinx.serialization.json.JsonPrimitive

/** Plugins that come with the app; a plugin folder with the same name replaces one of these. */
object BuiltInPlugins {
    private fun p(name: String, type: String, label: String, labelRu: String, default: Any? = null, options: List<String> = emptyList()) =
        PluginParam(name, type, label, labelRu, when (default) {
            null -> kotlinx.serialization.json.JsonNull
            is Number -> JsonPrimitive(default)
            is Boolean -> JsonPrimitive(default)
            else -> JsonPrimitive(default.toString())
        }, options)

    private const val TIER_PICK = """
var tier = null;
for (var i = 0; i < labels.length; i++) if (!params.tier || labels[i].name === params.tier) { tier = labels[i]; if (params.tier) break; }
if (!params.tier) { for (var i = 0; i < labels.length; i++) if (/^(phones|phonemes|phone|ph)$/i.test(labels[i].name)) tier = labels[i]; }
if (!tier) { report = 'No such tier'; return; }
"""

    val all: List<Pair<PluginInfo, String>> = listOf(
        PluginInfo(
            "replace-labels", "Replace labels by a table", "Заменить метки по таблице",
            "One pair per line: old=new. Whole labels only.", "По паре в строке: старое=новое. Только метки целиком.",
            parameters = listOf(
                p("table", "text", "Table", "Таблица", "pau=SP\nbr=AP"),
                p("tier", "string", "Tier (empty = phonemes)", "Слой (пусто — фонемы)", ""),
            ),
        ) to TIER_PICK + """
var map = {};
params.table.split(/\r?\n/).forEach(function (l) { var k = l.indexOf('='); if (k > 0) map[l.slice(0, k).trim()] = l.slice(k + 1).trim(); });
var n = 0;
tier.intervals.forEach(function (iv) { if (map.hasOwnProperty(iv.text)) { iv.text = map[iv.text]; n++; } });
report = n + ' replaced';
""",
        PluginInfo(
            "shift-labels", "Shift all labels", "Сдвинуть всю разметку",
            "Moves every boundary of every tier by the same amount.", "Сдвигает все границы всех слоёв на одну величину.",
            parameters = listOf(p("ms", "float", "Shift, ms (negative = earlier)", "Сдвиг, мс (минус — раньше)", 10.0)),
        ) to """
var d = params.ms / 1000;
labels.forEach(function (t) { t.intervals.forEach(function (iv) { iv.start = Math.max(0, iv.start + d); iv.end = Math.max(0, iv.end + d); }); });
""",
        PluginInfo(
            "merge-short", "Merge short intervals", "Слить короткие интервалы",
            "Intervals shorter than the limit join the previous one.", "Интервалы короче порога присоединяются к предыдущему.",
            parameters = listOf(
                p("ms", "float", "Shorter than, ms", "Короче, мс", 20.0),
                p("tier", "string", "Tier (empty = phonemes)", "Слой (пусто — фонемы)", ""),
            ),
        ) to TIER_PICK + """
var out = [], n = 0;
tier.intervals.forEach(function (iv) {
  if (out.length && (iv.end - iv.start) * 1000 < params.ms) { out[out.length - 1].end = iv.end; n++; } else out.push(iv);
});
tier.intervals = out;
report = n + ' merged';
""",
        PluginInfo(
            "fill-pauses", "Name empty intervals", "Назвать пустые интервалы",
            "Empty intervals and the usual pause names get one name.", "Пустые интервалы и обычные паузы получают одно имя.",
            parameters = listOf(
                p("name", "string", "Pause name", "Имя паузы", "SP"),
                p("also", "string", "Also rename (space separated)", "Также переименовать (через пробел)", "pau sil sp"),
                p("tier", "string", "Tier (empty = phonemes)", "Слой (пусто — фонемы)", ""),
            ),
        ) to TIER_PICK + """
var also = params.also.split(/\s+/).filter(function (s) { return s; });
var n = 0;
tier.intervals.forEach(function (iv) { if (iv.text === '' || also.indexOf(iv.text) >= 0) { if (iv.text !== params.name) n++; iv.text = params.name; } });
report = n + ' renamed';
""",
        PluginInfo(
            "prefix-suffix", "Add or remove a prefix / suffix", "Добавить или убрать префикс / суффикс",
            parameters = listOf(
                p("where", "enum", "Where", "Где", "prefix", listOf("prefix", "suffix")),
                p("action", "enum", "Action", "Действие", "add", listOf("add", "remove")),
                p("text", "string", "Text", "Текст", ""),
                p("tier", "string", "Tier (empty = phonemes)", "Слой (пусто — фонемы)", ""),
            ),
        ) to TIER_PICK + """
var t = params.text, n = 0;
tier.intervals.forEach(function (iv) {
  if (!iv.text) return;
  var s = iv.text;
  if (params.action === 'add') s = params.where === 'prefix' ? t + s : s + t;
  else if (params.where === 'prefix' && s.indexOf(t) === 0) s = s.slice(t.length);
  else if (params.where === 'suffix' && s.slice(-t.length) === t) s = s.slice(0, s.length - t.length);
  if (s !== iv.text) { iv.text = s; n++; }
});
report = n + ' changed';
""",
        PluginInfo(
            "oto-set-value", "Set an oto value", "Задать значение oto", target = "oto",
            description = "Sets or changes one value for the entries whose alias matches. In the expression: v = current value, e = the entry.",
            descriptionRu = "Задаёт или меняет одно значение у записей, чей псевдоним подходит. В выражении: v — текущее значение, e — запись.",
            parameters = listOf(
                p("value", "enum", "Value", "Значение", "preutterance", listOf("offset", "overlap", "preutterance", "consonant", "cutoff")),
                p("expr", "string", "New value (expression)", "Новое значение (выражение)", "v + 10"),
                p("alias", "string", "Alias matches (regular expression)", "Псевдоним подходит (регулярное выражение)", ".*"),
                p("keep", "boolean", "Keep other markers in place on the timeline", "Остальные маркеры не сдвигать по времени", false),
            ),
        ) to """
var re = new RegExp(params.alias), n = 0;
var f = new Function('v', 'e', 'return (' + params.expr + ');');
entries.forEach(function (e) {
  if (!re.test(e.alias)) return;
  var old = e[params.value], v = f(old, e);
  if (typeof v !== 'number' || isNaN(v)) return;
  if (params.keep && params.value === 'offset') {
    var d = v - old;
    e.overlap -= d; e.preutterance -= d; e.consonant -= d; if (e.cutoff < 0) e.cutoff += d;
  }
  e[params.value] = v; n++;
});
report = n + ' entries changed';
""",
        PluginInfo(
            "oto-sort", "Sort oto entries", "Сортировать записи oto", target = "oto",
            parameters = listOf(p("by", "enum", "By", "По", "sample", listOf("sample", "alias", "offset"))),
        ) to """
entries.sort(function (a, b) {
  if (params.by === 'offset') return a.sample === b.sample ? a.offset - b.offset : (a.sample < b.sample ? -1 : 1);
  var x = a[params.by], y = b[params.by];
  return x < y ? -1 : x > y ? 1 : a.offset - b.offset;
});
""",
        PluginInfo(
            "oto-duplicates", "Mark or remove duplicate aliases", "Повторы псевдонимов", target = "oto",
            description = "Finds aliases used more than once; the later ones get a number or are removed.",
            descriptionRu = "Находит псевдонимы, которые встречаются больше одного раза; следующие получают номер или удаляются.",
            parameters = listOf(p("action", "enum", "Action", "Действие", "number", listOf("number", "remove"))),
        ) to """
var seen = {}, out = [], n = 0;
entries.forEach(function (e) {
  if (!seen[e.alias]) { seen[e.alias] = 1; out.push(e); return; }
  seen[e.alias]++; n++;
  if (params.action === 'number') { e.alias = e.alias + seen[e.alias]; out.push(e); }
});
entries.length = 0; out.forEach(function (e) { entries.push(e); });
report = n + ' duplicates';
""",
        PluginInfo(
            "oto-prefix-suffix", "Alias prefix / suffix", "Префикс / суффикс псевдонимов", target = "oto",
            parameters = listOf(
                p("where", "enum", "Where", "Где", "suffix", listOf("prefix", "suffix")),
                p("action", "enum", "Action", "Действие", "add", listOf("add", "remove")),
                p("text", "string", "Text", "Текст", "C4"),
                p("alias", "string", "Only aliases matching (regular expression)", "Только подходящие псевдонимы (регулярное выражение)", ".*"),
            ),
        ) to """
var re = new RegExp(params.alias), t = params.text, n = 0;
entries.forEach(function (e) {
  if (!re.test(e.alias)) return;
  var s = e.alias;
  if (params.action === 'add') s = params.where === 'prefix' ? t + s : s + t;
  else if (params.where === 'prefix' && s.indexOf(t) === 0) s = s.slice(t.length);
  else if (params.where === 'suffix' && s.slice(-t.length) === t) s = s.slice(0, s.length - t.length);
  if (s !== e.alias) { e.alias = s; n++; }
});
report = n + ' changed';
""",
        PluginInfo(
            "oto-remove", "Remove oto entries", "Удалить записи oto", target = "oto",
            parameters = listOf(p("alias", "string", "Alias matches (regular expression)", "Псевдоним подходит (регулярное выражение)", "^$")),
        ) to """
var re = new RegExp(params.alias), before = entries.length;
var keep = entries.filter(function (e) { return !re.test(e.alias); });
entries.length = 0; keep.forEach(function (e) { entries.push(e); });
report = (before - entries.length) + ' removed';
""",
    )
}
