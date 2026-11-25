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
    @Test fun olderApiMidtonesChooseTheHigherContrastText() {
        assertTrue(usesDarkText(TextTone.WALLPAPER, WallpaperPalette(0x959595, null)))
        assertFalse(usesDarkText(TextTone.WALLPAPER, WallpaperPalette(0x6E6E6E, null)))
    }
    @Test fun cachedAppearanceLastsOnlyUntilWallpaperArrives() {
        val cached = HomeAppearance(true, 0x456789)
        assertTrue(initialDarkText(TextTone.WALLPAPER, null, cached))
        assertEquals(cached.accentSource, initialAccentSource(Accent.WALLPAPER, 0x398B81, cached))
        // The activity removes the initial snapshot on a response, including unavailable colors.
        assertFalse(initialDarkText(TextTone.WALLPAPER, WallpaperPalette(0, false), null))
        assertFalse(initialDarkText(TextTone.WALLPAPER, null, null))
        assertEquals(0x123456, initialAccentSource(Accent.WALLPAPER, 0x123456, null))
    }
    @Test fun explicitChoicesOverrideCachedAppearance() {
        val cached = HomeAppearance(true, 0x456789)
        assertFalse(initialDarkText(TextTone.WHITE, null, cached))
        assertTrue(initialDarkText(TextTone.BLACK, null, cached.copy(darkText = false)))
        assertEquals(Accent.IRIS.rgb, initialAccentSource(Accent.IRIS, 0, cached))
    }
    @Test fun lineHeightScalesWithEachTextSize() {
        assertEquals(listOf(22.5f, 27.5f, 32.5f), HomeSize.entries.map { it.lineHeightSp })
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
        val value = HomeThemeSettings(TextTone.BLACK, Accent.IRIS, HomeSize.LARGE, ScrollMotion.NONE)
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
    @Test fun oldThemeFilesDefaultToLowerLeftMotionWithoutLosingChoices() {
        var file = """{"tone":"BLACK","accent":"IRIS","size":"LARGE"}"""
        val store = HomeThemeStore({ file }, { file = it }, {})
        val old = HomeThemeSettings(TextTone.BLACK, Accent.IRIS, HomeSize.LARGE)
        assertEquals(ReadValue(old, false), store.read())
        store.save(old.copy(scrollMotion = ScrollMotion.NONE))
        assertEquals(old.copy(scrollMotion = ScrollMotion.NONE), store.read().value)
        store.save(old)
        assertEquals(old, store.read().value)
    }
    @Test fun futureMotionValueIsPreservedWhenChangingOtherSettings() {
        var file = """{"scrollMotion":"FUTURE_MOTION","future":42}"""
        val store = HomeThemeStore({ file }, { file = it }, {})
        assertEquals(ScrollMotion.LOWER_LEFT, store.read().value!!.scrollMotion)
        store.save(store.read().value!!.copy(size = HomeSize.SMALL))
        assertEquals("FUTURE_MOTION", Json.parseToJsonElement(file).jsonObject["scrollMotion"]!!.jsonPrimitive.content)
        assertEquals(42, Json.parseToJsonElement(file).jsonObject["future"]!!.jsonPrimitive.int)
        store.save(store.read().value!!.copy(scrollMotion = ScrollMotion.NONE))
        assertEquals(ScrollMotion.NONE, store.read().value!!.scrollMotion)
    }
    @Test fun brokenFilesDefaultLogAndCannotBeOverwritten() {
        for (broken in listOf("{", "[]", "null", "", """{"tone":123}""", """{"size":null}""", """{"scrollMotion":false}""")) {
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
