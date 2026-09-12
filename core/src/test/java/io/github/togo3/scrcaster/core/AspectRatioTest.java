package io.github.togo3.scrcaster.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Freezes the shared aspect-ratio math used by both the Android and desktop frontends, and the
 * even-pixel crop contract required by the scrcpy server.
 */
public class AspectRatioTest {
    private static final Pattern CROP =
            Pattern.compile("^(\\d+):(\\d+):(\\d+):(\\d+)$");

    @Test
    public void deviceRatioKeepsTheSourceGeometry() {
        assertEquals(0.0, AspectRatio.targetRatio(AspectRatio.Ratio.DEVICE, true, ""), 0.0);
        assertEquals(1.0, AspectRatio.targetRatio(AspectRatio.Ratio.SQUARE, false, ""), 0.0);
        assertEquals(16.0 / 9, AspectRatio.targetRatio(AspectRatio.Ratio.WIDE, true, ""), 0.0);
        assertEquals(9.0 / 16, AspectRatio.targetRatio(AspectRatio.Ratio.WIDE, false, ""), 0.0);
        assertEquals(4.0 / 3, AspectRatio.targetRatio(AspectRatio.Ratio.CLASSIC, true, ""), 0.0);
    }

    @Test
    public void customRatioAcceptsColonAndDecimalForms() {
        assertEquals(21.0 / 9, AspectRatio.targetRatio(AspectRatio.Ratio.CUSTOM, true, "21:9"), 1e-9);
        assertEquals(21.0 / 9, AspectRatio.parseRatio(" 21 : 9 "), 1e-9);
        assertEquals(1.78, AspectRatio.parseRatio("1.78"), 1e-9);
    }

    @Test
    public void invalidCustomRatioIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AspectRatio.parseRatio(""));
        assertThrows(IllegalArgumentException.class, () -> AspectRatio.parseRatio("wide"));
    }

    /**
     * Characterization: a zero height is not a division-by-zero in floating point, so "16:0"
     * currently yields an infinite ratio instead of an error. Recorded so a later validation
     * change is visible; nothing in the UI can produce this text today.
     */
    @Test
    public void zeroHeightCustomRatioYieldsInfinity() {
        assertEquals(Double.POSITIVE_INFINITY, AspectRatio.parseRatio("16:0"), 0.0);
    }

    @Test
    public void orientationFollowsTheSource() {
        // A landscape target applied to a portrait source is turned around instead of flipping it.
        assertEquals(9.0 / 16, AspectRatio.orientToSource(16.0 / 9, 1080, 1920), 1e-9);
        assertEquals(16.0 / 9, AspectRatio.orientToSource(16.0 / 9, 1920, 1080), 1e-9);
        assertEquals(0.0, AspectRatio.orientToSource(0.0, 1920, 1080), 0.0);
    }

    @Test
    public void cropAlwaysUsesEvenBoundaries() {
        for (boolean landscape : new boolean[] {false, true}) {
            for (int[] source : new int[][] {{1080, 1920}, {1920, 1080}, {1440, 3200}, {800, 600}}) {
                for (double target : new double[] {0, 1, 4.0 / 3, 16.0 / 9, 21.0 / 9, 0.4}) {
                    assertCrop(AspectRatio.cropForRatio(source[0], source[1], landscape, target), source, target);
                    assertCrop(
                            AspectRatio.cropForExactRatio(source[0], source[1], landscape, target), source, target);
                }
            }
        }
    }

    @Test
    public void deviceRatioCoversTheWholeScreen() {
        // A non-positive target keeps the device ratio, so the crop fills the source.
        assertEquals("1080:1920:0:0", AspectRatio.cropForRatio(1080, 1920, false, 0));
    }

    @Test
    public void squareCropOfALandscapePanelTrimsTheLongEdge() {
        assertEquals("1080:1080:420:0", AspectRatio.cropForRatio(1920, 1080, false, 1));
    }

    @Test
    public void windowFillsReuseTheSameMath() {
        assertEquals(
                AspectRatio.cropForRatio(1080, 1920, false, 21.0 / 9),
                AspectRatio.fillCrop(1080, 1920, false, 2100, 900));
        assertEquals(
                AspectRatio.cropForExactRatio(1080, 1920, false, 21.0 / 9),
                AspectRatio.coverCrop(1080, 1920, false, 2100, 900));
    }

    private static void assertCrop(String crop, int[] source, double target) {
        Matcher matcher = CROP.matcher(crop);
        assertTrue("unparsable crop " + crop, matcher.matches());
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        int x = Integer.parseInt(matcher.group(3));
        int y = Integer.parseInt(matcher.group(4));
        assertEquals("crop width must be even: " + crop, 0, width % 2);
        assertEquals("crop height must be even: " + crop, 0, height % 2);
        assertTrue("crop must fit the source: " + crop, width <= source[0] && height <= source[1]);
        assertTrue("crop must be centred: " + crop, x + width <= source[0] && y + height <= source[1]);
        if (target > 0) {
            double actual = (double) width / height;
            // cropForRatio() turns a target around when it disagrees with the panel orientation,
            // so both the ratio and its reciprocal are acceptable outcomes here.
            double reciprocal = 1.0 / target;
            double error = Math.min(
                    Math.abs(actual - target) / target,
                    Math.abs(actual - reciprocal) / reciprocal);
            assertTrue(
                    "crop ratio " + actual + " drifted from target " + target + " (" + crop + ")",
                    error < 0.02);
        }
    }
}
