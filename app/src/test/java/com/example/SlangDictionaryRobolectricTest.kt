package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.SlangDictionaryManager
import com.example.ai.SlangOverrideRule
import com.example.data.local.DialogueStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SlangDictionaryRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        SlangDictionaryManager.dictionaryFile(context).delete()
    }

    @Test
    fun `initializes local JSON dictionary file inside app internal filesDir`() {
        val cfg = SlangDictionaryManager.ensureInitialized(context)
        val file = SlangDictionaryManager.dictionaryFile(context)

        assertTrue(file.exists())
        assertEquals(context.filesDir, file.parentFile)
        assertTrue(cfg.enabled)
        assertTrue(cfg.rules.any { it.sourceTerm == "fucker" && it.hindiReplacement == "चुतिया" })
    }

    @Test
    fun `resolves context-dependent slang rules over generic fallbacks`() {
        val cfg = SlangDictionaryManager.ensureInitialized(context)

        val intimateMatch = SlangDictionaryManager.resolveMatches(
            config = cfg,
            englishText = "Touch that wet pussy right now",
            prevContext = "in the bedroom",
            dialogueStyle = DialogueStyle.RAW_UNCENSORED
        )
        assertEquals("चूत", intimateMatch.first().rule.hindiReplacement)

        val cowardMatch = SlangDictionaryManager.resolveMatches(
            config = cfg,
            englishText = "Don't be such a pussy, come fight!",
            prevContext = "you are scared and afraid",
            dialogueStyle = DialogueStyle.RAW_UNCENSORED
        )
        assertEquals("फट्टू", cowardMatch.first().rule.hindiReplacement)

        val phrasePriority = SlangDictionaryManager.resolveMatches(
            config = cfg,
            englishText = "What the fuck are you doing, fucker?"
        )
        assertEquals(2, phrasePriority.size)
        assertEquals("ये क्या बकचोदी है", phrasePriority[0].rule.hindiReplacement)
        assertEquals("चुतिया", phrasePriority[1].rule.hindiReplacement)
    }

    @Test
    fun `placeholder protection and fallback override censored machine translation`() {
        val cfg = SlangDictionaryManager.ensureInitialized(context)

        // Direct full-line override
        val directPrep = SlangDictionaryManager.prepareProtectedText(cfg, "Fucker!")
        assertEquals("चुतिया!", directPrep.directOverrideHindi)

        // In-sentence placeholder protection (simulates ML Kit leaving token untouched)
        val sentencePrep = SlangDictionaryManager.prepareProtectedText(
            config = cfg,
            englishText = "I wanna fuck you tonight, baby"
        )
        assertTrue(sentencePrep.protectedEnglish.contains("ZXQ0QXZ"))
        val simulatedMlKitOut = "मैं आज रात तुम्हें ZXQ0QXZ चाहता हूँ, बेबी"
        val restored = SlangDictionaryManager.restoreAndOverride(simulatedMlKitOut, sentencePrep)
        assertTrue(restored.contains("चोदना"))
        assertFalse(restored.contains("ZXQ0QXZ"))

        // Fallback if machine translator replaced word with censored 'मूर्ख'
        val fallbackPrep = SlangDictionaryManager.prepareProtectedText(cfg, "You are a fucker")
        val censoredOut = "तुम एक मूर्ख हो"
        val overridden = SlangDictionaryManager.restoreAndOverride(censoredOut, fallbackPrep)
        assertEquals("तुम एक चुतिया हो", overridden)
    }

    @Test
    fun `custom rule CRUD and raw JSON import persist cleanly`() {
        SlangDictionaryManager.ensureInitialized(context)
        val afterAdd = SlangDictionaryManager.upsertRule(
            context,
            SlangOverrideRule(
                id = "custom_bhidu",
                sourceTerm = "bro",
                hindiReplacement = "भिड़ू",
                contextKeywords = listOf("mumbai", "gang"),
                note = "Tapori Mumbai slang"
            )
        )
        assertTrue(afterAdd.rules.any { it.id == "custom_bhidu" && it.hindiReplacement == "भिड़ू" })

        val toggled = SlangDictionaryManager.toggleRuleEnabled(context, "custom_bhidu", false)
        assertFalse(toggled.rules.first { it.id == "custom_bhidu" }.enabled)

        val rawJson = SlangDictionaryManager.readRawJson(context)
        assertTrue(rawJson.contains("custom_bhidu"))
        val reimported = SlangDictionaryManager.importRawJson(context, rawJson)
        assertEquals(toggled.rules.size, reimported.rules.size)

        val afterDelete = SlangDictionaryManager.deleteRule(context, "custom_bhidu")
        assertFalse(afterDelete.rules.any { it.id == "custom_bhidu" })
    }

    @Test
    fun `manual review sentence and phrase edits save permanently and match future videos`() {
        SlangDictionaryManager.ensureInitialized(context)

        // 1. Save full English sentence ending with punctuation
        val afterSentenceSave = SlangDictionaryManager.upsertManualOverrideRule(
            context = context,
            sourceTerm = "What the heck are you doing?",
            hindiReplacement = "तू ये क्या कर रहा है?",
            wholeWord = true
        )
        val savedSentenceRule = afterSentenceSave.rules.first {
            it.sourceTerm == "What the heck are you doing?"
        }
        assertTrue(savedSentenceRule.id.startsWith("review_"))
        assertEquals("तू ये क्या कर रहा है?", savedSentenceRule.hindiReplacement)

        // Reload from JSON file and verify direct override without duplicate '??'
        val reloaded = SlangDictionaryManager.loadConfig(context)
        val directPrep = SlangDictionaryManager.prepareProtectedText(
            config = reloaded,
            englishText = "What the heck are you doing?"
        )
        assertEquals("तू ये क्या कर रहा है?", directPrep.directOverrideHindi)

        // 2. Save custom word/phrase and verify in-sentence override
        SlangDictionaryManager.upsertManualOverrideRule(
            context = context,
            sourceTerm = "brother",
            hindiReplacement = "भाईजान",
            wholeWord = true
        )
        val updatedCfg = SlangDictionaryManager.loadConfig(context)
        val sentencePrep = SlangDictionaryManager.prepareProtectedText(
            config = updatedCfg,
            englishText = "Listen carefully, brother"
        )
        assertTrue(sentencePrep.protectedEnglish.contains("ZXQ0QXZ"))
        val restored = SlangDictionaryManager.restoreAndOverride(
            machineTranslatedHindi = "ध्यान से सुनो, ZXQ0QXZ",
            prep = sentencePrep
        )
        assertEquals("ध्यान से सुनो, भाईजान", restored)

        // 3. Re-saving same English sentence updates existing rule in place instead of duplicating
        val afterUpdate = SlangDictionaryManager.upsertManualOverrideRule(
            context = context,
            sourceTerm = "what the heck are you doing?",
            hindiReplacement = "अबे तू ये क्या कर रहा है?",
            wholeWord = true
        )
        val matchingRules = afterUpdate.rules.filter {
            it.sourceTerm.equals("what the heck are you doing?", ignoreCase = true)
        }
        assertEquals(1, matchingRules.size)
        assertEquals(savedSentenceRule.id, matchingRules.first().id)
        assertEquals("अबे तू ये क्या कर रहा है?", matchingRules.first().hindiReplacement)
    }
}
