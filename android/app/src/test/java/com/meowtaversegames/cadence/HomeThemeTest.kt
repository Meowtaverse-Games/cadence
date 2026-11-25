package com.meowtaversegames.cadence

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HomeThemeTest {
    @Test fun wallpaperHintOverridesPrimaryOnNewerApis() {
        assertTrue(usesDarkText(TextTone.WALLPAPER, WallpaperPalette(0, true)))
        assertFalse(usesDarkText(TextTone.WALLPAPER, WallpaperPalette(0xFFFFFF, false)))
    }
    @Test fun olderApisUsePrimaryAndMissingColorsUseWhite() {
        assertTrue(usesDarkText(TextTone.WALLPAPER, WallpaperPalette(0xEEEEEE, null)))
        assertFalse(usesDarkText(TextTone.WALLPAPER, WallpaperPalette(0x222222, null)))
        assertFalse(usesDarkText(TextTone.WALLPAPER, null))
    }
    @Test fun explicitToneIgnoresWallpaper() {
        assertTrue(usesDarkText(TextTone.BLACK, null))
        assertFalse(usesDarkText(TextTone.WHITE, WallpaperPalette(0xFFFFFF, true)))
    }
    @Test fun accentsMeetContrastAcrossRgbSpaceAndPresets() {
        val samples = buildList {
            for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) add((r shl 16) or (g shl 8) or b)
            addAll(Accent.entries.mapNotNull { it.rgb })
        }
        for (dark in listOf(false, true)) for (source in samples) {
            val corrected = readableAccent(source, dark)
            assertTrue(contrastRatio(corrected, accentBackground(dark)) >= 3.0)
            if (contrastRatio(source, accentBackground(dark)) >= 3.0) assertEquals(source, corrected)
        }
        assertEquals(21.0, contrastRatio(0, 0xFFFFFF), 0.001)
    }
    @Test fun everySizeHasAccessibleTouchTargetAndIncreasingText() {
        assertTrue(HomeSize.entries.all { it.rowDp >= 48 })
        assertTrue(HomeSize.entries.zipWithNext().all { (a, b) -> a.textSp < b.textSp && a.rowDp < b.rowDp })
    }
    @Test fun missingFileDefaultsAndSelectionsRoundTrip() {
        var file: String? = null
        val store = HomeThemeStore({ file }, { file = it }, { fail("unexpected read failure") })
        assertEquals(ReadValue(HomeThemeSettings(), false), store.read())
        val value = HomeThemeSettings(TextTone.BLACK, Accent.IRIS, HomeSize.LARGE)
        store.save(value)
        assertEquals(ReadValue(value, false), store.read())
        store.save(HomeThemeSettings())
        assertEquals(HomeThemeSettings(), store.read().value)
    }
    @Test fun unknownKeysAndFutureEnumValuesSurviveChanges() {
        var file = """{"future":{"version":3,"flags":[true]},"tone":"FUTURE_TONE","size":"SMALL"}"""
        val store = HomeThemeStore({ file }, { file = it }, {})
        assertEquals(HomeThemeSettings(size = HomeSize.SMALL), store.read().value)
        store.save(HomeThemeSettings(accent = Accent.LEAF, size = HomeSize.SMALL))
        val doc = Json.parseToJsonElement(file).jsonObject
        assertEquals("FUTURE_TONE", doc["tone"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"version":3,"flags":[true]}"""), doc["future"])
        assertEquals(Accent.LEAF, store.read().value!!.accent)
    }
    @Test fun brokenFilesDefaultLogAndCannotBeOverwritten() {
        for (broken in listOf("{", "[]", "null", "", """{"tone":123}""", """{"size":null}""")) {
            var writes = 0; var logs = 0
            val store = HomeThemeStore({ broken }, { writes++ }, { logs++ })
            assertEquals(ReadValue(HomeThemeSettings(), true), store.read())
            assertThrows(Exception::class.java) { store.save(HomeThemeSettings()) }
            assertEquals(0, writes)
            assertEquals(2, logs)
        }
    }
    @Test fun ioFailureAndCorruptionAfterReadAreProtected() {
        var failed = false; var writes = 0
        val store = HomeThemeStore({ if (failed) error("unreadable") else "{}" }, { writes++ }, {})
        assertFalse(store.read().failed)
        failed = true
        assertThrows(Exception::class.java) { store.save(HomeThemeSettings()) }
        assertEquals(0, writes)
        assertTrue(store.read().failed)
    }
}
