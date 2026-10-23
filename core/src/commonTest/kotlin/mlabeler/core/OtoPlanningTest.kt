package mlabeler.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import mlabeler.core.format.OtoEntry
import mlabeler.core.oto.*

class OtoPlanningTest {
    private fun entry(sample: String = "a.wav", alias: String = "a", offset: Double = 100.0) =
        OtoEntry(sample, alias, offset, 200.0, -600.0, 100.0, 30.0)
    private fun timings() = listOf(SyllableTiming(Syllable("さ", "s", "a"), 0.1, 0.2, 0.6),
        SyllableTiming(Syllable("い", "", "i"), 0.6, 0.6, 1.0))

    @Test fun oldSettingsLoadAndRoundTripProfiles() {
        val old = Json.decodeFromString<AutoOtoSettings>("{}")
        assertEquals(AutoOtoSettings(), old)
        val s = old.copy(style = RecStyle.CVVC, aliases = AliasScript.Both, aliasSuffix = "_C4", rules = listOf(OtoRule(OtoEntryKind.VC, preutterance = -10.0)))
        assertEquals(s, Json.decodeFromString<AutoOtoSettings>(Json.encodeToString(s)))
    }

    @Test fun bothSpellingsShareTimingsAndHaveNoDuplicates() {
        val original = AutoOto.entries("sai.wav", timings(), 1100.0, AutoOtoSettings(style = RecStyle.CVVC))
        val both = AutoOto.entries("sai.wav", timings(), 1100.0, AutoOtoSettings(style = RecStyle.CVVC, aliases = AliasScript.Both))
        assertEquals(both.size, both.map { it.alias }.distinct().size)
        for (e in original) {
            for (alias in OtoRules.aliases(e.alias, AliasScript.Both)) assertEquals(e.copy(alias = alias), both.first { it.alias == alias })
        }
        assertTrue(both.any { it.alias == "a い" }); assertTrue(both.any { it.alias == "a i" })
    }

    @Test fun zeroRulePreservesExactValues() {
        val s = AutoOtoSettings(style = RecStyle.CVVC)
        assertEquals(AutoOto.entries("sai.wav", timings(), 1100.0, s),
            AutoOto.entries("sai.wav", timings(), 1100.0, s.copy(rules = OtoEntryKind.entries.map { OtoRule(it) })))
    }

    @Test fun nasalVcAndNasalVowelUseDifferentRules() {
        assertEquals(OtoEntryKind.VC, OtoRules.kind("a n"))
        assertEquals(OtoEntryKind.VV, OtoRules.kind("a ん"))
        assertEquals(OtoEntryKind.Initial, OtoRules.kind("START か", "START ", "END"))
        assertEquals(OtoEntryKind.Ending, OtoRules.kind("a END", "START ", "END"))
    }

    @Test fun rulesStayInsideRecording() {
        val e = OtoRule(OtoEntryKind.CV, offset = 900.0, consonant = -1000.0, cutoff = -900.0).apply(entry(), 1000.0)
        val a = e.absolute(1000.0)
        assertTrue(a.left >= 0 && a.right <= 1000)
        assertTrue(a.left <= a.preutterance && a.preutterance <= a.consonant && a.consonant <= a.right)
        assertTrue(e.cutoff < 0)
    }

    @Test fun exclusionsPreserveOldValuesAndOrder() {
        val old = listOf(entry(), entry(alias = "i"), entry("b.wav", "u"))
        val plan = OtoPlan(old, listOf(entry(offset = 120.0), entry(alias = "e")), setOf("a.wav"), true)
        assertEquals(old, plan.apply(emptySet()))
        val add = plan.rows.indexOfFirst { it.action == OtoPlan.Action.Add }
        assertEquals(listOf(old[0], old[1], entry(alias = "e"), old[2]), plan.apply(setOf(add)))
        val all = plan.apply(plan.rows.indices.toSet())
        assertEquals(listOf(entry(offset = 120.0), entry(alias = "e"), old[2]), all)
    }

    @Test fun keepModeDoesNotReplaceOrRemoveExisting() {
        val old = listOf(entry(), entry(alias = "i"))
        val plan = OtoPlan(old, listOf(entry(offset = 200.0), entry(alias = "u")), setOf("a.wav"), false)
        assertEquals(old + entry(alias = "u"), plan.apply(plan.rows.indices.toSet()))
    }

    @Test fun failedFilesAreOutsidePlan() {
        val old = listOf(entry("failed.wav"))
        val plan = OtoPlan(old, emptyList(), emptySet(), true)
        assertEquals(old, plan.apply(plan.rows.indices.toSet()))
    }

    @Test fun collisionsAreExposedAcrossRecordings() {
        val plan = OtoPlan(listOf(entry("old.wav")), listOf(entry("new.wav")), setOf("new.wav"), false)
        assertTrue(plan.rows.single().conflict)
    }

    @Test fun readinessUnderstandsKanaAndPitchGroups() {
        val entries = listOf(entry(alias = "a_C4"), entry(alias = "か_C4"), entry(alias = "a_D4"))
        val issues = OtoReadiness.check(entries, Syllables.fromName("a_ka"), RecStyle.CV, setOf("a.wav"), suffix = "_C4")
        assertTrue(issues.isEmpty(), issues.toString())
        val missing = OtoReadiness.check(entries, Syllables.fromName("a_ka"), RecStyle.CV, setOf("a.wav"), suffix = "_D4")
        assertEquals(listOf("か_D4"), missing.map { it.alias })
    }

    @Test fun readinessRequiresVvAndReportsMissingAudioAndDuplicates() {
        val es = listOf(entry(alias = "a"), entry(alias = "a"), entry(alias = "- a"))
        val issues = OtoReadiness.check(es, Syllables.fromName("a_i"), RecStyle.CVVC, emptySet())
        assertTrue(issues.any { it.kind == OtoReadiness.Kind.MissingAlias && it.alias == "a い" })
        assertTrue(issues.any { it.kind == OtoReadiness.Kind.DuplicateAlias })
        assertTrue(issues.any { it.kind == OtoReadiness.Kind.MissingSample })
    }

    @Test fun openUtauVcvReportsFallbackRatherThanMissing() {
        val issues = OtoReadiness.check(listOf(entry(alias = "か")), Syllables.fromName("ka"), RecStyle.VCV,
            setOf("a.wav"), phonemizer = OtoReadiness.Phonemizer.OpenUtauJaVcv)
        assertTrue(issues.isNotEmpty())
        assertTrue(issues.all { it.kind == OtoReadiness.Kind.FallbackAlias })
        val romaji = OtoReadiness.check(listOf(entry(alias = "ka")), Syllables.fromName("ka"), RecStyle.VCV,
            setOf("a.wav"), phonemizer = OtoReadiness.Phonemizer.OpenUtauJaVcv)
        assertTrue(romaji.any { it.kind == OtoReadiness.Kind.MissingAlias })
    }

    @Test fun openUtauCvvcUsesPalatalVcAndItsSubstitute() {
        val issues = OtoReadiness.check(listOf(entry(alias = "き"), entry(alias = "i k")), Syllables.fromName("ki"), RecStyle.CVVC,
            setOf("a.wav"), phonemizer = OtoReadiness.Phonemizer.OpenUtauJaCvvc)
        assertTrue(issues.any { it.alias == "i ky" && it.sample == "i k" && it.kind == OtoReadiness.Kind.FallbackAlias })
    }
}
