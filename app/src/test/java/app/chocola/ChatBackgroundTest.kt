package app.chocola

import app.chocola.data.*
import app.chocola.ui.PanelBounds
import app.chocola.ui.PanelGrowth
import androidx.compose.ui.geometry.Rect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatBackgroundTest {
    @Test fun wideImagesFillHeightAndPanAcrossTheEntireCroppedWidth() {
        val centre = BackgroundGeometry.crop(2000f, 1000f, 400f, 900f, .5f, 1f)
        assertEquals(900f, centre.height, .01f); assertEquals(1800f, centre.width, .01f)
        assertEquals(-700f, centre.left, .01f); assertEquals(0f, centre.top, .01f)
        assertEquals(0f, BackgroundGeometry.crop(2000f, 1000f, 400f, 900f, 0f, 1f).left, 0f)
        assertEquals(-1400f, BackgroundGeometry.crop(2000f, 1000f, 400f, 900f, 1f, 1f).left, .01f)
    }
    @Test fun unusuallyTallPhotosStillCoverTheScreenWithoutBlankSideBars() {
        val crop = BackgroundGeometry.crop(200f, 2000f, 400f, 900f, .8f, 1f)
        assertEquals(400f, crop.width, 0f); assertEquals(0f, crop.left, 0f)
        assertTrue(crop.height >= 900f); assertTrue(crop.top <= 0f)
        val zoomed = BackgroundGeometry.crop(200f, 2000f, 400f, 900f, .5f, 1.2f)
        assertTrue(zoomed.width > crop.width)
    }
    @Test fun allBackgroundSettingsRoundTripAndOlderPreferencesKeepTheirOriginalAppearance() {
        val design = ChatBackground(preset = "custom", image = "/private/background.image", imageTone = "#667788", color = "#F1E6D8", colorEnd = "#EAEFF4", angle = 135f,
            blur = 23f, brightness = -.14f, contrast = .8f, saturation = .6f, warmth = .1f, veil = .3f, veilColor = "#DDEEFF", horizontal = .7f, zoom = 1.3f, edgeOpacity = .9f, edgeDepth = 140f, text = "light")
        assertEquals(design, ChatBackground.from(design.json()))
        assertEquals(design, Preferences.from(Preferences(chatBackground = design).json()).chatBackground)
        assertEquals(ChatBackground(), Preferences.from(JSONObject()).chatBackground)
    }
    @Test fun malformedSettingsCannotProduceInfiniteCropsOrInvalidColors() {
        val d = ChatBackground(horizontal = Float.NaN, zoom = 500f, blur = -3f, brightness = Float.POSITIVE_INFINITY, color = "invalid").normalized()
        assertEquals(.5f, d.horizontal, 0f); assertEquals(1.8f, d.zoom, 0f); assertEquals(0f, d.blur, 0f)
        assertTrue(d.brightness.isFinite()); assertEquals("#FAFAF9", d.color)
    }
    @Test fun longPressMenuFitsBothTopAndBottomCorners() {
        val viewport = Rect(0f, 24f, 400f, 860f)
        for (point in listOf(Rect(3f, 30f, 19f, 46f), Rect(370f, 810f, 386f, 826f))) {
            val target = PanelBounds.target(point, viewport, 222f, 212f, 12f, PanelGrowth.CONTEXT, 5f)
            assertTrue(target.left >= 12f); assertTrue(target.right <= 388f)
            assertTrue(target.top >= viewport.top); assertTrue(target.bottom <= 848f)
        }
    }
    @Test fun thinkingChangesNeverSilentlyRaiseTheOutputBudget() {
        val p = ModelCatalog.models.first { it.model == "deepseek-flash" }.apply(Provider()).copy(maxTokens = 4096)
        val s = Session(reasoningModel = ThinkingControls.modelKey(p), reasoningLevel = "max")
        assertEquals(4096, ThinkingControls.effective(p, s).maxTokens)
        val explicit = s.copy(outputLimit = 16384, outputModel = ThinkingControls.modelKey(p))
        assertEquals(16384, ThinkingControls.effective(p, Session.from(explicit.json())).maxTokens)
        assertEquals("max", ThinkingControls.effective(p, explicit).reasoningEffort)
        assertEquals(4096, ThinkingControls.effective(p, explicit.copy(outputModel = "other-model")).maxTokens)
        assertEquals(4096, p.maxTokens)
    }
}
