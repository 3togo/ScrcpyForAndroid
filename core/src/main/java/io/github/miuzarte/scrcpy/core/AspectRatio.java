package io.github.miuzarte.scrcpy.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aspect-ratio / crop math shared by the desktop (Java) and Android (Kotlin) frontends.
 * Written against Java 8 language features so it compiles on the desktop and desugars
 * cleanly for Android. The crop rect is in the device natural orientation.
 */
public final class AspectRatio {
    /** Target aspect ratio to crop the device screen to. DEVICE means keep the device ratio. */
    public enum Ratio {
        DEVICE("Device (full screen)"),
        SQUARE("1:1"),
        CLASSIC("4:3 / 3:4"),
        WIDE("16:9 / 9:16"),
        CUSTOM("Custom…");
        private final String label;
        Ratio(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private AspectRatio() { }

    /** Target displayed width/height ratio for the chosen mode; 0 keeps the device ratio. */
    public static double targetRatio(Ratio mode, boolean landscape, String custom) {
        if (mode == Ratio.DEVICE) return 0;
        if (mode == Ratio.SQUARE) return 1;
        if (mode == Ratio.CLASSIC) return landscape ? 4.0 / 3 : 3.0 / 4;
        if (mode == Ratio.WIDE) return landscape ? 16.0 / 9 : 9.0 / 16;
        return parseRatio(custom); // CUSTOM
    }

    /** Parse a user-entered aspect ratio such as "16:9", "9:16", "21:9" or "1.78". */
    public static double parseRatio(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Enter the aspect ratio, for example 21:9 or 1.78.");
        try {
            if (s.matches("\\d+(?:\\.\\d+)?")) return Double.parseDouble(s);
            Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*:\\s*(\\d+(?:\\.\\d+)?)$").matcher(s);
            if (m.matches()) return Double.parseDouble(m.group(1)) / Double.parseDouble(m.group(2));
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Enter the aspect ratio as width:height, for example 21:9.");
    }

    /** Orient a width/height target to match the current displayed source dimensions. */
    public static double orientToSource(double target, int sourceW, int sourceH) {
        if (!(target > 0) || sourceW <= 0 || sourceH <= 0) return target;
        boolean sourceLandscape = sourceW >= sourceH;
        return ((target >= 1) == sourceLandscape) ? target : 1.0 / target;
    }

    /**
     * Crop rect matching a target width/height ratio. A non-positive target keeps the
     * device ratio. Cropping trims the source symmetrically (no distortion); scrcpy
     * requires even pixel boundaries, so the result is rounded down to even values.
     * <p>
     * The crop is expressed in the device natural orientation and is rotated together
     * with the device, so a target wider than tall would turn a portrait screen into a
     * landscape picture (and vice versa). The target is therefore turned around when it
     * does not match the current device orientation, so the mirrored output always keeps
     * the orientation of the source: portrait in, portrait out.
     */
    public static String cropForRatio(int naturalW, int naturalH, boolean landscape, double target) {
        double t = target > 0 ? target : (double) naturalW / naturalH;
        if ((t >= 1) != landscape) t = 1.0 / t;
        return cropForOrientedRatio(naturalW, naturalH, landscape, t);
    }

    /** Crop to the exact displayed ratio, even when it changes portrait/landscape orientation. */
    public static String cropForExactRatio(int naturalW, int naturalH, boolean landscape, double target) {
        double t = target > 0 ? target : (double) naturalW / naturalH;
        return cropForOrientedRatio(naturalW, naturalH, landscape, t);
    }

    private static String cropForOrientedRatio(int naturalW, int naturalH, boolean landscape, double t) {
        int cropW, cropH;
        if (landscape) { // rotation swaps the axes: crop height becomes the displayed width
            cropW = Math.min(naturalW, (int) Math.round(naturalH / t));
            cropH = (int) Math.round(cropW * t);
            if (cropH > naturalH) { cropH = naturalH; cropW = (int) Math.round(cropH / t); }
        } else {
            cropH = Math.min(naturalH, (int) Math.round(naturalW / t));
            cropW = (int) Math.round(cropH * t);
            if (cropW > naturalW) { cropW = naturalW; cropH = (int) Math.round(cropW / t); }
        }
        cropW &= ~1; cropH &= ~1; // even boundaries required by scrcpy
        cropW = Math.max(2, Math.min(cropW, naturalW));
        cropH = Math.max(2, Math.min(cropH, naturalH));
        int x = ((naturalW - cropW) / 2) & ~1;
        int y = ((naturalH - cropH) / 2) & ~1;
        return cropW + ":" + cropH + ":" + x + ":" + y;
    }

    /** Crop rect matching a window aspect ratio, for fullscreen fit-to-fill. */
    public static String fillCrop(int naturalW, int naturalH, boolean landscape, int windowW, int windowH) {
        return cropForRatio(naturalW, naturalH, landscape, (double) windowW / windowH);
    }

    /** Crop to the receiver ratio for short-edge/cover fill. */
    public static String coverCrop(int naturalW, int naturalH, boolean landscape, int windowW, int windowH) {
        return cropForExactRatio(naturalW, naturalH, landscape, (double) windowW / windowH);
    }
}
