package app.chocola

import app.chocola.data.*
import app.chocola.ui.*
import androidx.compose.ui.geometry.Rect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InteractionPresentationTest {
    @Test fun compactBecomesDefaultWhileExplicitLargerAndNewComfortSizesSurvive() {
        assertEquals(.92f, Preferences.from(JSONObject()).fontScale, 0f)
        assertEquals(.92f, Preferences.from(JSONObject().put("fontScale", 1f)).fontScale, 0f)
        assertEquals(1.1f, Preferences.from(JSONObject().put("fontScale", 1.1f)).fontScale, 0f)
        assertEquals(1f, Preferences.from(Preferences(fontScale = 1f).json()).fontScale, 0f)
        assertEquals(.92f, FontSizes.restore(Float.NaN, 2), 0f)
    }
    @Test fun oldStatusSettingsBecomeAnEquivalentEditableConfiguration() {
        val preferences = Preferences(statusStyle = "ripple", statusSize = 2, statusSpeed = 3200, statusStrength = .8f, statusDuration = 400,
            statusAutoWidth = false, statusWidth = 204, statusWidthDuration = 500)
        val restored = Preferences.from(preferences.json()).pill
        assertEquals("ripple", restored.style); assertEquals(36f, restored.height, 0f)
        assertEquals(13f, restored.fontSize, 0f); assertEquals(3200, restored.period)
        assertEquals(.8f, restored.strength, 0f); assertEquals(400, restored.appearanceDuration)
        assertFalse(restored.autoWidth); assertEquals(204f, restored.fixedWidth, 0f); assertEquals(500, restored.widthDuration)
    }
    @Test fun exportedCustomAppearanceRoundTripsAllParametersWithoutApplicationSecrets() {
        val design = PillDesign(style = "aurora", height = 44f, minWidth = 96f, maxWidth = 280f, autoWidth = false, fixedWidth = 210f, padding = 19f, corner = 14f,
            fontSize = 14f, fontWeight = 600, letterSpacing = .2f, ink = "#202124", paper = "#F7F8FA", lightA = "#8899EE", lightB = "#9966DD", lightC = "#55BB99",
            period = 4200, strength = .85f, band = 1.2f, angle = 20f, direction = "alternate", textEffect = "glide", textDuration = 450, feather = 36f, textDirection = "reverse",
            widthDuration = 600, widthCurve = "spring", stiffness = 250f, damping = .95f, appearanceDuration = 400, entrance = "scale", travel = 8f, startScale = .9f,
            shadowBlur = 26f, shadowAlpha = .12f, shadowOffset = 4f, shadowColor = "#10131A")
        val exported = JSONObject(design.export())
        assertEquals("chocola.status.v1", exported.getString("schema"))
        assertEquals(design, PillDesign.from(exported.getJSONObject("configuration")))
        assertEquals(design, Preferences.from(Preferences(statusDesign = design).json()).pill)
        assertEquals(setOf("schema", "configuration"), exported.keys().asSequence().toSet())
    }
    @Test fun malformedAndExtremeConfigurationsCannotCreateInvalidDrawDimensions() {
        val d = PillDesign.from(JSONObject().put("height", -20).put("minWidth", 900).put("maxWidth", -2).put("period", 0)
            .put("strength", "NaN").put("ink", "invalid").put("shadowBlur", -200).put("direction", "unexpected"))
        assertTrue(d.height >= 24); assertTrue(d.maxWidth >= d.minWidth)
        assertTrue(d.period >= 600); assertTrue(d.strength.isFinite()); assertTrue(d.shadowBlur > 0)
        assertEquals("#222326", d.ink); assertEquals("forward", d.direction)
        assertEquals(d, PillDesign.from(d.json()))
    }
    @Test fun permissionMenuRemainsInsideTheWindowAndExpandsFromTheCapsule() {
        val source = Rect(270f, 700f, 370f, 734f)
        val viewport = Rect(0f, 30f, 400f, 800f)
        val target = AccessMenuBounds.target(source, viewport, 336f, 348f, 14f)
        assertTrue(target.left >= 14f); assertTrue(target.right <= 386f)
        assertEquals(source.bottom, target.bottom, 0f)
        assertEquals(source, AccessMenuBounds.between(source, target, 0f))
        assertEquals(target, AccessMenuBounds.between(source, target, 1f))
        val middle = AccessMenuBounds.between(source, target, .5f)
        assertTrue(middle.width > source.width); assertTrue(middle.top < source.top)
        assertEquals(source.bottom, middle.bottom, 0f)
        assertTrue(AccessMenuBounds.target(source, Rect(0f, 0f, 40f, 20f), 336f, 348f, 14f).height > 0)
    }
    @Test fun latestButtonStaysHiddenForNearbyHistoryAndUsesHysteresisAtItsBoundary() {
        assertFalse(LatestVisibility.show(1200f, 600, false))
        assertTrue(LatestVisibility.show(2000f, 600, false))
        assertTrue(LatestVisibility.show(1000f, 600, true))
        assertFalse(LatestVisibility.show(800f, 600, true))
        assertFalse(LatestVisibility.show(1000f, 0, false))
    }
    @Test fun proseSoftBreaksRespectChineseEnglishAndExplicitHardBreaks() {
        assertEquals("中文自然连读。下一句。", ProseLayout.softBreaks("中文自然\n连读。\n下一句。"))
        assertEquals("Read the current result.", ProseLayout.softBreaks("Read the\ncurrent result."))
        assertEquals("第一行\n第二行", ProseLayout.softBreaks("第一行  \n第二行"))
        assertEquals("one\ntwo", ProseLayout.softBreaks("one\\\ntwo"))
        val raw = "printf 'hello'  \npath\\\nend"
        val code = RichDocuments.prepare(MarkdownBlock(MarkdownType.CODE, raw), false)
        assertEquals(raw, code.content.text)
        assertEquals("中文自然连读", RichDocuments.prepare(MarkdownBlock(MarkdownType.TEXT, "中文自然\n连读"), false).content.text)
    }
}
