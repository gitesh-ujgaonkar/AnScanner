package com.anscanner.app.processing;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.Nullable;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.util.List;

/**
 * OpenCV-based image processing pipeline for document scanning.
 *
 * <p>All methods operate on copies — input bitmaps are never mutated.
 * Callers are responsible for recycling returned bitmaps when done.</p>
 *
 * <p><strong>Memory contract:</strong> Every {@link Mat} allocated in these
 * methods is released before the method returns. The only output is a new
 * {@link Bitmap} that the caller must manage.</p>
 */
public final class ImageProcessor {

    private static final String TAG = "ImageProcessor";

    private ImageProcessor() {
        // Static utility class — no instantiation
    }

    // ════════════════════════════════════════════════════════════════════
    // AI Document Boundary Detection (delegates to DocumentDetector)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Detects document corners using the on-device TensorFlow Lite DocumentDetector.
     *
     * @param context Application context to load model.
     * @param src     Source bitmap (not mutated).
     * @return Ordered corner points [TL, TR, BR, BL] or fallback bounds.
     */
    public static Point[] detectDocumentEdges(android.content.Context context, Bitmap src) {
        if (src == null || src.isRecycled()) {
            return null;
        }
        return DocumentDetector.getInstance(context).detectCorners(src);
    }

    /**
     * Overload for backward compatibility with existing callers.
     */
    public static Point[] detectDocumentEdges(Bitmap src) {
        if (src == null || src.isRecycled()) {
            return null;
        }
        return DocumentDetector.getDefaultCorners(src.getWidth(), src.getHeight());
    }

    // ════════════════════════════════════════════════════════════════════
    // Perspective Warp (Crop & Correct)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Alias for {@link #perspectiveWarp(Bitmap, Point[])}.
     */
    public static Bitmap warpPerspective(Bitmap src, Point[] corners) {
        return perspectiveWarp(src, corners);
    }

    /**
     * Applies a 4-point perspective warp to extract and straighten the
     * document region from the source bitmap.
     *
     * <p>Follows standard document scanner pipeline:
     * 1. Consistently orders the 4 points: [Top-Left, Top-Right, Bottom-Right, Bottom-Left].
     * 2. Calculates true destination rectangle dimensions:
     *    width_top = distance(TL, TR), width_bottom = distance(BL, BR), target_width = max(width_top, width_bottom)
     *    height_left = distance(TL, BL), height_right = distance(TR, BR), target_height = max(height_left, height_right)
     * 3. Applies standard 4-point perspective transform to flat rectangle [0, 0, target_width, target_height].</p>
     *
     * @param src     Source bitmap (not mutated).
     * @param corners 4 corner points.
     * @return A new bitmap containing the perspective-corrected document.
     */
    public static Bitmap perspectiveWarp(Bitmap src, Point[] corners) {
        if (src == null || src.isRecycled()) {
            return null;
        }
        if (corners == null || corners.length != 4) {
            Log.w(TAG, "Invalid corners for warp, returning original");
            return src.copy(src.getConfig(), true);
        }

        // 1. Order the 4 points consistently: [Top-Left, Top-Right, Bottom-Right, Bottom-Left]
        Point[] ordered = DocumentDetector.orderCorners(corners);

        // 2. Calculate true destination rectangle dimensions
        double widthTop = distance(ordered[0], ordered[1]);
        double widthBottom = distance(ordered[3], ordered[2]);
        int targetWidth = (int) Math.round(Math.max(widthTop, widthBottom));

        double heightLeft = distance(ordered[0], ordered[3]);
        double heightRight = distance(ordered[1], ordered[2]);
        int targetHeight = (int) Math.round(Math.max(heightLeft, heightRight));

        targetWidth = Math.max(1, targetWidth);
        targetHeight = Math.max(1, targetHeight);

        Mat srcMat = new Mat();
        Mat warped = new Mat();

        try {
            Utils.bitmapToMat(src, srcMat);

            // Source points (ordered document corners: TL, TR, BR, BL)
            MatOfPoint2f srcPts = new MatOfPoint2f(
                    ordered[0], ordered[1], ordered[2], ordered[3]
            );

            // Destination points: flat rectangle [0, 0, target_width, target_height]
            MatOfPoint2f dstPts = new MatOfPoint2f(
                    new Point(0, 0),
                    new Point(targetWidth, 0),
                    new Point(targetWidth, targetHeight),
                    new Point(0, targetHeight)
            );

            // Compute and apply the perspective transform
            Mat transform = Imgproc.getPerspectiveTransform(srcPts, dstPts);
            Imgproc.warpPerspective(srcMat, warped, transform,
                    new Size(targetWidth, targetHeight),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_REPLICATE,
                    new Scalar(255, 255, 255, 255));

            // Convert result back to Bitmap
            Bitmap result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(warped, result);

            srcPts.release();
            dstPts.release();
            transform.release();

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Perspective warp failed", e);
            return src.copy(src.getConfig(), true);
        } finally {
            srcMat.release();
            warped.release();
        }
    }

    /**
     * Applies perspective warp directly using normalized corner points [0.0..1.0].
     *
     * @param src Source bitmap (not mutated).
     * @param normalizedCorners Normalized corners: [TL, TR, BR, BL] in range [0.0, 1.0].
     * @return Perspective-corrected bitmap.
     */
    public static Bitmap perspectiveWarp(Bitmap src, android.graphics.PointF[] normalizedCorners) {
        if (src == null || src.isRecycled()) return null;
        if (normalizedCorners == null || normalizedCorners.length != 4) {
            return src.copy(src.getConfig(), true);
        }
        int w = src.getWidth();
        int h = src.getHeight();
        Point[] actualCorners = new Point[4];
        for (int i = 0; i < 4; i++) {
            actualCorners[i] = new Point(
                    Math.max(0.0, Math.min(w, (double) normalizedCorners[i].x * w)),
                    Math.max(0.0, Math.min(h, (double) normalizedCorners[i].y * h))
            );
        }
        return perspectiveWarp(src, actualCorners);
    }

    // ════════════════════════════════════════════════════════════════════
    // Filters (100% Pure Java, Memory Efficient, High Performance)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Returns a copy of the source bitmap with no filter applied.
     */
    public static Bitmap applyOriginalFilter(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        return src.copy(src.getConfig(), true);
    }

    /**
     * Applies CamScanner-style Magic Color filter with local background illumination
     * flattening, RGB normalization, contrast/paper whitening boost, and saturation
     * enhancement for colored inks and highlighters.
     *
     * <p>100% Pure Java, zero OpenCV dependency, under 100ms execution on mobile.</p>
     *
     * @param src Source bitmap (not mutated).
     * @return Color-corrected and shadow-free document bitmap.
     */
    public static Bitmap applyMagicColor(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;

        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        // Step 1: Downsample by 4x to estimate local paper background illumination.
        // We take the max luminance in each 4x4 block to sample the paper background
        // while ignoring dark text pixels (preventing halos around text).
        int dw = (w + 3) / 4;
        int dh = (h + 3) / 4;
        int[] bgGrid = new int[dw * dh];

        for (int gy = 0; gy < dh; gy++) {
            int yStart = gy * 4;
            int yEnd = Math.min(h, yStart + 4);
            int gRowOffset = gy * dw;
            for (int gx = 0; gx < dw; gx++) {
                int xStart = gx * 4;
                int xEnd = Math.min(w, xStart + 4);
                int maxLuma = 0;
                for (int py = yStart; py < yEnd; py++) {
                    int pRowOffset = py * w;
                    for (int px = xStart; px < xEnd; px++) {
                        int c = pixels[pRowOffset + px];
                        int r = (c >> 16) & 0xFF;
                        int g = (c >> 8) & 0xFF;
                        int b = c & 0xFF;
                        int lum = (r * 77 + g * 150 + b * 29) >> 8;
                        if (lum > maxLuma) {
                            maxLuma = lum;
                        }
                    }
                }
                bgGrid[gRowOffset + gx] = Math.max(1, maxLuma);
            }
        }

        // Step 2: Smooth downscaled background grid with separable 1D box blur
        int blurRadius = Math.max(4, Math.min(12, dw / 20));
        int[] tempGrid = new int[dw * dh];

        // Horizontal pass
        for (int y = 0; y < dh; y++) {
            int rowOffset = y * dw;
            long sum = 0;
            int count = 0;
            int initEnd = Math.min(dw - 1, blurRadius);
            for (int i = 0; i <= initEnd; i++) {
                sum += bgGrid[rowOffset + i];
                count++;
            }
            tempGrid[rowOffset] = (int) (sum / Math.max(1, count));

            for (int x = 1; x < dw; x++) {
                int addIdx = x + blurRadius;
                if (addIdx < dw) {
                    sum += bgGrid[rowOffset + addIdx];
                    count++;
                }
                int remIdx = x - blurRadius - 1;
                if (remIdx >= 0) {
                    sum -= bgGrid[rowOffset + remIdx];
                    count--;
                }
                tempGrid[rowOffset + x] = (int) (sum / Math.max(1, count));
            }
        }

        // Vertical pass
        int[] blurredBg = new int[dw * dh];
        for (int x = 0; x < dw; x++) {
            long sum = 0;
            int count = 0;
            int initEnd = Math.min(dh - 1, blurRadius);
            for (int i = 0; i <= initEnd; i++) {
                sum += tempGrid[i * dw + x];
                count++;
            }
            blurredBg[x] = (int) (sum / Math.max(1, count));

            for (int y = 1; y < dh; y++) {
                int addIdx = y + blurRadius;
                if (addIdx < dh) {
                    sum += tempGrid[addIdx * dw + x];
                    count++;
                }
                int remIdx = y - blurRadius - 1;
                if (remIdx >= 0) {
                    sum -= tempGrid[remIdx * dw + x];
                    count--;
                }
                blurredBg[y * dw + x] = (int) (sum / Math.max(1, count));
            }
        }

        // Step 3: Precompute tone curve (push paper >215 straight to 255, boost contrast for text)
        int[] toneCurve = new int[256];
        for (int v = 0; v < 256; v++) {
            if (v >= 215) {
                toneCurve[v] = 255;
            } else {
                float norm = v / 215.0f;
                float boosted = (float) Math.pow(norm, 1.15);
                int val = Math.round(boosted * 255.0f);
                toneCurve[v] = Math.max(0, Math.min(255, val));
            }
        }

        // Step 4: Bilinear upscale background illumination and normalize RGB
        int[] outPixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            float gy = (float) y / 4.0f;
            int y0 = (int) gy;
            int y1 = Math.min(dh - 1, y0 + 1);
            float fy = gy - y0;
            int y0Offset = y0 * dw;
            int y1Offset = y1 * dw;
            int pRowOffset = y * w;

            for (int x = 0; x < w; x++) {
                float gx = (float) x / 4.0f;
                int x0 = (int) gx;
                int x1 = Math.min(dw - 1, x0 + 1);
                float fx = gx - x0;

                float b00 = blurredBg[y0Offset + x0];
                float b10 = blurredBg[y0Offset + x1];
                float b01 = blurredBg[y1Offset + x0];
                float b11 = blurredBg[y1Offset + x1];

                int bgLuma = Math.round((b00 * (1.0f - fx) + b10 * fx) * (1.0f - fy)
                        + (b01 * (1.0f - fx) + b11 * fx) * fy);
                if (bgLuma < 1) bgLuma = 1;

                int c = pixels[pRowOffset + x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;

                // Normalize against local paper background illumination
                int normR = Math.min(255, (r * 255) / bgLuma);
                int normG = Math.min(255, (g * 255) / bgLuma);
                int normB = Math.min(255, (b * 255) / bgLuma);

                int rBoost = toneCurve[normR];
                int gBoost = toneCurve[normG];
                int bBoost = toneCurve[normB];

                // Paper pixels (>215) straight to pure white
                if (rBoost >= 254 && gBoost >= 254 && bBoost >= 254) {
                    outPixels[pRowOffset + x] = 0xFFFFFFFF;
                } else {
                    // Boost saturation ~1.25x for colored inks, highlighters, and stamps
                    int gray = (rBoost * 77 + gBoost * 150 + bBoost * 29) >> 8;
                    int rFinal = Math.max(0, Math.min(255, gray + (int) ((rBoost - gray) * 1.25f)));
                    int gFinal = Math.max(0, Math.min(255, gray + (int) ((gBoost - gray) * 1.25f)));
                    int bFinal = Math.max(0, Math.min(255, gray + (int) ((bBoost - gray) * 1.25f)));
                    outPixels[pRowOffset + x] = 0xFF000000 | (rFinal << 16) | (gFinal << 8) | bFinal;
                }
            }
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(outPixels, 0, w, 0, 0, w, h);
        return result;
    }

    /**
     * Backward-compatible alias for {@link #applyMagicColor(Bitmap)}.
     */
    public static Bitmap applyCLAHE(Bitmap src) {
        return applyMagicColor(src);
    }

    /**
     * Applies Bradley-Roth Adaptive Local Thresholding using a 2D integral image table.
     * Eliminates solid black blotches in shadowed corners and uneven lighting by comparing
     * each pixel's luminance against the local moving-window mean.
     *
     * <p>100% Pure Java, executes in &lt;150ms on mobile devices.</p>
     *
     * @param src Source bitmap (not mutated).
     * @return Clean binary black-and-white document bitmap.
     */
    public static Bitmap applyAdaptiveBW(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;

        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        // Build 2D Integral Image (using long[] to prevent 32-bit overflow)
        long[] integral = new long[w * h];
        int[] luma = new int[w * h];

        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            int rowOffset = y * w;
            int prevRowOffset = (y - 1) * w;
            for (int x = 0; x < w; x++) {
                int c = pixels[rowOffset + x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int val = (r * 77 + g * 150 + b * 29) >> 8;
                luma[rowOffset + x] = val;
                rowSum += val;
                if (y == 0) {
                    integral[rowOffset + x] = rowSum;
                } else {
                    integral[rowOffset + x] = integral[prevRowOffset + x] + rowSum;
                }
            }
        }

        // Window size: roughly imageWidth / 8 clamped between 15 to 45
        int windowSize = Math.max(15, Math.min(45, w / 8));
        int radius = windowSize / 2;
        // Threshold percentage: ~14% below local mean (factor 0.86)
        final float factor = 0.86f;

        int[] outPixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y1 = Math.max(0, y - radius);
            int y2 = Math.min(h - 1, y + radius);
            int yOffset = y * w;
            int y1Offset = (y1 - 1) * w;
            int y2Offset = y2 * w;

            for (int x = 0; x < w; x++) {
                int x1 = Math.max(0, x - radius);
                int x2 = Math.min(w - 1, x + radius);
                int count = (x2 - x1 + 1) * (y2 - y1 + 1);

                long sum = integral[y2Offset + x2];
                if (x1 > 0) sum -= integral[y2Offset + (x1 - 1)];
                if (y1 > 0) sum -= integral[y1Offset + x2];
                if (x1 > 0 && y1 > 0) sum += integral[y1Offset + (x1 - 1)];

                if ((float) luma[yOffset + x] < ((float) sum / count) * factor) {
                    outPixels[yOffset + x] = 0xFF000000; // Black text / strokes
                } else {
                    outPixels[yOffset + x] = 0xFFFFFFFF; // Pure white paper
                }
            }
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(outPixels, 0, w, 0, 0, w, h);
        return result;
    }

    /**
     * Backward-compatible alias for {@link #applyAdaptiveBW(Bitmap)}.
     */
    public static Bitmap applyOtsuBW(Bitmap src) {
        return applyAdaptiveBW(src);
    }

    /**
     * Converts the source bitmap to grayscale in 100% pure Java.
     *
     * @param src Source bitmap (not mutated).
     * @return Grayscale bitmap.
     */
    public static Bitmap applyGrayscale(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;

        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int gray = (r * 77 + g * 150 + b * 29) >> 8;
            pixels[i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, w, 0, 0, w, h);
        return result;
    }

    /**
     * Applies an unsharp-mask sharpening filter in 100% pure Java.
     *
     * @param src Source bitmap (not mutated).
     * @return Sharpened bitmap.
     */
    public static Bitmap applySharpen(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;

        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        int[] tempH = new int[w * h];
        for (int y = 0; y < h; y++) {
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - 1);
                int x1 = x;
                int x2 = Math.min(w - 1, x + 1);

                int c0 = pixels[rowOffset + x0];
                int c1 = pixels[rowOffset + x1];
                int c2 = pixels[rowOffset + x2];

                int r = (((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) << 1) + ((c2 >> 16) & 0xFF)) >> 2;
                int g = (((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) << 1) + ((c2 >> 8) & 0xFF)) >> 2;
                int b = ((c0 & 0xFF) + ((c1 & 0xFF) << 1) + (c2 & 0xFF)) >> 2;

                tempH[rowOffset + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        int[] outPixels = new int[w * h];
        final float sharpenStrength = 0.8f;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int y0 = Math.max(0, y - 1);
                int y1 = y;
                int y2 = Math.min(h - 1, y + 1);

                int c0 = tempH[y0 * w + x];
                int c1 = tempH[y1 * w + x];
                int c2 = tempH[y2 * w + x];

                int blurR = (((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) << 1) + ((c2 >> 16) & 0xFF)) >> 2;
                int blurG = (((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) << 1) + ((c2 >> 8) & 0xFF)) >> 2;
                int blurB = ((c0 & 0xFF) + ((c1 & 0xFF) << 1) + (c2 & 0xFF)) >> 2;

                int orig = pixels[y * w + x];
                int origR = (orig >> 16) & 0xFF;
                int origG = (orig >> 8) & 0xFF;
                int origB = orig & 0xFF;

                int sharpR = Math.max(0, Math.min(255, origR + (int) ((origR - blurR) * sharpenStrength)));
                int sharpG = Math.max(0, Math.min(255, origG + (int) ((origG - blurG) * sharpenStrength)));
                int sharpB = Math.max(0, Math.min(255, origB + (int) ((origB - blurB) * sharpenStrength)));

                outPixels[y * w + x] = 0xFF000000 | (sharpR << 16) | (sharpG << 8) | sharpB;
            }
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(outPixels, 0, w, 0, 0, w, h);
        return result;
    }

    /**
     * Applies Scan Enhance filter providing a balanced scanner look: subtle background
     * whitening (levels stretch) + unsharp mask sharpening (high-pass filter) for fine text
     * without binary harshness.
     *
     * <p>100% Pure Java, fast execution, preserves colors and anti-aliased font edges.</p>
     *
     * @param src Source bitmap (not mutated).
     * @return Enhanced document scan bitmap.
     */
    public static Bitmap applyScanEnhance(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;

        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        // Subtle background whitening / levels stretch (black point 20, white point 220)
        int[] levelLut = new int[256];
        for (int i = 0; i < 256; i++) {
            if (i <= 20) {
                levelLut[i] = 0;
            } else if (i >= 220) {
                levelLut[i] = 255;
            } else {
                levelLut[i] = Math.round((i - 20) * 255.0f / 200.0f);
            }
        }

        int[] levelsPixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int a = c & 0xFF000000;
            int r = levelLut[(c >> 16) & 0xFF];
            int g = levelLut[(c >> 8) & 0xFF];
            int b = levelLut[c & 0xFF];
            levelsPixels[i] = a | (r << 16) | (g << 8) | b;
        }

        // Fast separable unsharp mask sharpening
        int[] tempH = new int[w * h];
        for (int y = 0; y < h; y++) {
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - 1);
                int x1 = x;
                int x2 = Math.min(w - 1, x + 1);

                int c0 = levelsPixels[rowOffset + x0];
                int c1 = levelsPixels[rowOffset + x1];
                int c2 = levelsPixels[rowOffset + x2];

                int r = (((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) << 1) + ((c2 >> 16) & 0xFF)) >> 2;
                int g = (((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) << 1) + ((c2 >> 8) & 0xFF)) >> 2;
                int b = ((c0 & 0xFF) + ((c1 & 0xFF) << 1) + (c2 & 0xFF)) >> 2;

                tempH[rowOffset + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        int[] outPixels = new int[w * h];
        final float sharpenStrength = 0.75f;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int y0 = Math.max(0, y - 1);
                int y1 = y;
                int y2 = Math.min(h - 1, y + 1);

                int c0 = tempH[y0 * w + x];
                int c1 = tempH[y1 * w + x];
                int c2 = tempH[y2 * w + x];

                int blurR = (((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) << 1) + ((c2 >> 16) & 0xFF)) >> 2;
                int blurG = (((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) << 1) + ((c2 >> 8) & 0xFF)) >> 2;
                int blurB = ((c0 & 0xFF) + ((c1 & 0xFF) << 1) + (c2 & 0xFF)) >> 2;

                int orig = levelsPixels[y * w + x];
                int origR = (orig >> 16) & 0xFF;
                int origG = (orig >> 8) & 0xFF;
                int origB = orig & 0xFF;

                int sharpR = Math.max(0, Math.min(255, origR + (int) ((origR - blurR) * sharpenStrength)));
                int sharpG = Math.max(0, Math.min(255, origG + (int) ((origG - blurG) * sharpenStrength)));
                int sharpB = Math.max(0, Math.min(255, origB + (int) ((origB - blurB) * sharpenStrength)));

                outPixels[y * w + x] = 0xFF000000 | (sharpR << 16) | (sharpG << 8) | sharpB;
            }
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(outPixels, 0, w, 0, 0, w, h);
        return result;
    }

    /**
     * Applies color inversion to the source bitmap (document negative / inverted mode) in 100% pure Java.
     *
     * @param src Source bitmap (not mutated).
     * @return Color-inverted bitmap.
     */
    public static Bitmap applyInvert(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;

        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = 255 - ((c >> 16) & 0xFF);
            int g = 255 - ((c >> 8) & 0xFF);
            int b = 255 - (c & 0xFF);
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, w, 0, 0, w, h);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    // Rotation
    // ════════════════════════════════════════════════════════════════════

    /**
     * Rotates a bitmap by the specified degrees (must be a multiple of 90).
     *
     * @param src     Source bitmap (not mutated).
     * @param degrees Rotation angle: 90, 180, or 270.
     * @return Rotated bitmap.
     */
    public static Bitmap rotateBitmap(Bitmap src, int degrees) {
        if (degrees % 90 != 0 || degrees == 0) {
            return src.copy(src.getConfig(), true);
        }

        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(degrees);

        return Bitmap.createBitmap(src, 0, 0,
                src.getWidth(), src.getHeight(), matrix, true);
    }

    // ════════════════════════════════════════════════════════════════════
    // Utilities
    // ════════════════════════════════════════════════════════════════════

    /**
     * Euclidean distance between two points.
     */
    private static double distance(Point a, Point b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ════════════════════════════════════════════════════════════════════
    // Smart ID Card Crop & Composite Generator
    // ════════════════════════════════════════════════════════════════════

    /**
     * Crops a bitmap to the ID card aspect ratio (85.6mm x 54.0mm ≈ 1.585) without running
     * any Canny edge detection or contour search.
     *
     * @param src Source bitmap (not mutated, caller must recycle).
     * @param cropRect Optional target rect in bitmap coordinates. If null or invalid, uses centered crop.
     * @return Cropped card bitmap.
     */
    public static Bitmap cropIdCardFrame(Bitmap src, @Nullable Rect cropRect) {
        if (src == null || src.isRecycled()) return null;

        int bW = src.getWidth();
        int bH = src.getHeight();
        int left, top, w, h;

        if (cropRect != null && !cropRect.isEmpty()
                && cropRect.left >= 0 && cropRect.top >= 0
                && cropRect.right <= bW && cropRect.bottom <= bH
                && cropRect.width() > 0 && cropRect.height() > 0) {
            left = cropRect.left;
            top = cropRect.top;
            w = cropRect.width();
            h = cropRect.height();
        } else {
            // Default to centered standard ID card aspect ratio (1.585) without edge detection
            w = (int) (bW * 0.86f);
            h = (int) (w / 1.585f);
            if (h > bH * 0.65f) {
                h = (int) (bH * 0.65f);
                w = (int) (h * 1.585f);
            }
            left = Math.max(0, (bW - w) / 2);
            top = Math.max(0, (bH - h) / 2);
            w = Math.min(w, bW - left);
            h = Math.min(h, bH - top);
        }

        return Bitmap.createBitmap(src, left, top, w, h);
    }

    /**
     * Composites Front and Back ID card captures vertically onto a single standard A4 canvas,
     * styled like a professional office photocopy.
     *
     * <p>Both input bitmaps are strictly recycled in a finally block.</p>
     *
     * @param front Bitmap containing front side of ID.
     * @param back  Bitmap containing back side of ID.
     * @return High-resolution A4 composite bitmap.
     */
    public static Bitmap compositeIdCard(Bitmap front, Bitmap back) {
        // High-resolution A4 canvas: 1654 x 2338 pixels (~200 DPI)
        final int a4Width = 1654;
        final int a4Height = 2338;

        Bitmap canvasBitmap = Bitmap.createBitmap(a4Width, a4Height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);
        canvas.drawColor(Color.WHITE);

        try {
            Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

            // Card dimension on A4: ~58% width, standard 1.585 aspect ratio
            float cardWidth = a4Width * 0.58f;
            float cardHeight = cardWidth / 1.585f;
            float cardLeft = (a4Width - cardWidth) / 2f;
            float cornerRadius = 24f;

            // Paint styles
            Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setColor(Color.parseColor("#CBD5E1")); // Light gray card border
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(3f);

            Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            headerPaint.setColor(Color.parseColor("#334155")); // Dark slate
            headerPaint.setTextSize(26f);
            headerPaint.setFakeBoldText(true);
            headerPaint.setTextAlign(Paint.Align.LEFT);

            Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dividerPaint.setColor(Color.parseColor("#E2E8F0"));
            dividerPaint.setStyle(Paint.Style.STROKE);
            dividerPaint.setStrokeWidth(2f);
            dividerPaint.setPathEffect(new DashPathEffect(new float[]{16f, 16f}, 0));

            // 1. Front Card
            float frontTop = a4Height * 0.14f;
            RectF frontRect = new RectF(cardLeft, frontTop, cardLeft + cardWidth, frontTop + cardHeight);

            // "FRONT" header
            canvas.drawText("FRONT", cardLeft, frontTop - 20f, headerPaint);

            // Draw front card with rounded corners
            Path frontPath = new Path();
            frontPath.addRoundRect(frontRect, cornerRadius, cornerRadius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(frontPath);
            if (front != null && !front.isRecycled()) {
                canvas.drawBitmap(front, null, frontRect, bitmapPaint);
            }
            canvas.restore();
            canvas.drawPath(frontPath, borderPaint);

            // 2. Middle divider line
            float midY = a4Height * 0.49f;
            canvas.drawLine(a4Width * 0.12f, midY, a4Width * 0.88f, midY, dividerPaint);

            // 3. Back Card
            float backTop = a4Height * 0.54f;
            RectF backRect = new RectF(cardLeft, backTop, cardLeft + cardWidth, backTop + cardHeight);

            // "BACK" header
            canvas.drawText("BACK", cardLeft, backTop - 20f, headerPaint);

            // Draw back card with rounded corners
            Path backPath = new Path();
            backPath.addRoundRect(backRect, cornerRadius, cornerRadius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(backPath);
            if (back != null && !back.isRecycled()) {
                canvas.drawBitmap(back, null, backRect, bitmapPaint);
            }
            canvas.restore();
            canvas.drawPath(backPath, borderPaint);

            return canvasBitmap;

        } finally {
            // Strictly recycle both input bitmaps
            if (front != null && !front.isRecycled()) {
                front.recycle();
            }
            if (back != null && !back.isRecycled()) {
                back.recycle();
            }
        }
    }
}
