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
     * @param src     Source bitmap (not mutated).
     * @param corners Ordered corner points: [TL, TR, BR, BL].
     * @return A new bitmap containing the perspective-corrected document,
     *         or the original bitmap copy if the warp fails.
     */
    public static Bitmap perspectiveWarp(Bitmap src, Point[] corners) {
        if (corners == null || corners.length != 4) {
            Log.w(TAG, "Invalid corners for warp, returning original");
            return src.copy(src.getConfig(), true);
        }

        Mat srcMat = new Mat();
        Mat warped = new Mat();

        try {
            Utils.bitmapToMat(src, srcMat);

            // Source points (the detected document corners)
            MatOfPoint2f srcPts = new MatOfPoint2f(
                    corners[0], corners[1], corners[2], corners[3]
            );

            // Calculate output dimensions from the corner distances
            double widthTop = distance(corners[0], corners[1]);
            double widthBottom = distance(corners[3], corners[2]);
            int maxWidth = (int) Math.max(widthTop, widthBottom);

            double heightLeft = distance(corners[0], corners[3]);
            double heightRight = distance(corners[1], corners[2]);
            int maxHeight = (int) Math.max(heightLeft, heightRight);

            // Destination points (a perfect rectangle)
            MatOfPoint2f dstPts = new MatOfPoint2f(
                    new Point(0, 0),
                    new Point(maxWidth - 1, 0),
                    new Point(maxWidth - 1, maxHeight - 1),
                    new Point(0, maxHeight - 1)
            );

            // Compute and apply the perspective transform
            Mat transform = Imgproc.getPerspectiveTransform(srcPts, dstPts);
            Imgproc.warpPerspective(srcMat, warped, transform,
                    new Size(maxWidth, maxHeight));

            // Convert result back to Bitmap
            Bitmap result = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888);
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

    // ════════════════════════════════════════════════════════════════════
    // Filters
    // ════════════════════════════════════════════════════════════════════

    /**
     * Returns a copy of the source bitmap with no filter applied.
     */
    public static Bitmap applyOriginalFilter(Bitmap src) {
        return src.copy(src.getConfig(), true);
    }

    /**
     * Applies CLAHE (Contrast Limited Adaptive Histogram Equalization)
     * to enhance document readability while preserving colors.
     *
     * <p>Pipeline: RGBA → BGR → LAB → CLAHE on L channel → LAB → BGR → RGBA → Bitmap</p>
     *
     * @param src Source bitmap (not mutated).
     * @return Enhanced bitmap with improved contrast.
     */
    public static Bitmap applyCLAHE(Bitmap src) {
        Mat rgba = new Mat();
        Mat bgr = new Mat();
        Mat lab = new Mat();

        try {
            Utils.bitmapToMat(src, rgba);
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab);

            // Split LAB channels
            List<Mat> labChannels = new java.util.ArrayList<>();
            Core.split(lab, labChannels);

            // Apply CLAHE to the L (lightness) channel
            CLAHE clahe = Imgproc.createCLAHE(3.0, new Size(8, 8));
            clahe.apply(labChannels.get(0), labChannels.get(0));

            // Merge channels back
            Core.merge(labChannels, lab);

            // Convert back to RGBA
            Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR);
            Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA);

            Bitmap result = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgba, result);

            // Release channel mats
            for (Mat channel : labChannels) {
                channel.release();
            }

            return result;

        } catch (Exception e) {
            Log.e(TAG, "CLAHE filter failed", e);
            return src.copy(src.getConfig(), true);
        } finally {
            rgba.release();
            bgr.release();
            lab.release();
        }
    }

    /**
     * Applies Otsu thresholding to produce a clean black-and-white document.
     *
     * <p>Pipeline: RGBA → Gray → GaussianBlur → Otsu threshold → Bitmap</p>
     *
     * @param src Source bitmap (not mutated).
     * @return Clean B&W bitmap.
     */
    public static Bitmap applyOtsuBW(Bitmap src) {
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat bw = new Mat();

        try {
            Utils.bitmapToMat(src, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

            // Light blur to reduce noise before thresholding
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);

            // Otsu's automatic threshold
            Imgproc.threshold(blurred, bw, 0, 255,
                    Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);

            Bitmap result = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            // Convert single-channel to RGBA for Bitmap compatibility
            Mat rgbaResult = new Mat();
            Imgproc.cvtColor(bw, rgbaResult, Imgproc.COLOR_GRAY2RGBA);
            Utils.matToBitmap(rgbaResult, result);
            rgbaResult.release();

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Otsu BW filter failed", e);
            return src.copy(src.getConfig(), true);
        } finally {
            rgba.release();
            gray.release();
            blurred.release();
            bw.release();
        }
    }

    /**
     * Converts the source bitmap to grayscale.
     *
     * <p>Pipeline: RGBA → GRAY → RGBA → Bitmap</p>
     *
     * @param src Source bitmap (not mutated).
     * @return Grayscale bitmap.
     */
    public static Bitmap applyGrayscale(Bitmap src) {
        Mat rgba = new Mat();
        Mat gray = new Mat();

        try {
            Utils.bitmapToMat(src, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

            // Convert single-channel back to RGBA for Bitmap compatibility
            Mat rgbaResult = new Mat();
            Imgproc.cvtColor(gray, rgbaResult, Imgproc.COLOR_GRAY2RGBA);

            Bitmap result = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgbaResult, result);
            rgbaResult.release();

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Grayscale filter failed", e);
            return src.copy(src.getConfig(), true);
        } finally {
            rgba.release();
            gray.release();
        }
    }

    /**
     * Applies an unsharp-mask sharpening filter.
     *
     * <p>Pipeline: RGBA → GaussianBlur → addWeighted(original, 1.5, blurred, -0.5) → Bitmap</p>
     *
     * @param src Source bitmap (not mutated).
     * @return Sharpened bitmap.
     */
    public static Bitmap applySharpen(Bitmap src) {
        Mat rgba = new Mat();
        Mat blurred = new Mat();
        Mat sharpened = new Mat();

        try {
            Utils.bitmapToMat(src, rgba);

            // Create a Gaussian-blurred copy
            Imgproc.GaussianBlur(rgba, blurred, new Size(0, 0), 3);

            // Unsharp mask: sharpened = original * 1.5 - blurred * 0.5
            Core.addWeighted(rgba, 1.5, blurred, -0.5, 0, sharpened);

            Bitmap result = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(sharpened, result);

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Sharpen filter failed", e);
            return src.copy(src.getConfig(), true);
        } finally {
            rgba.release();
            blurred.release();
            sharpened.release();
        }
    }

    /**
     * Applies adaptive thresholding for a high-contrast, shadow-free
     * document look ("Scan Enhance").
     *
     * <p>Pipeline: RGBA → GRAY → adaptiveThreshold (Gaussian, block=15, C=10)
     * → RGBA → Bitmap</p>
     *
     * @param src Source bitmap (not mutated).
     * @return High-contrast document bitmap.
     */
    public static Bitmap applyScanEnhance(Bitmap src) {
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat thresh = new Mat();

        try {
            Utils.bitmapToMat(src, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

            // Adaptive threshold removes shadows and produces a clean,
            // high-contrast binary document image.
            Imgproc.adaptiveThreshold(
                    gray, thresh, 255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY,
                    15,   // block size (must be odd)
                    10    // constant C subtracted from the mean
            );

            // Convert single-channel back to RGBA for Bitmap compatibility
            Mat rgbaResult = new Mat();
            Imgproc.cvtColor(thresh, rgbaResult, Imgproc.COLOR_GRAY2RGBA);

            Bitmap result = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgbaResult, result);
            rgbaResult.release();

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Scan enhance filter failed", e);
            return src.copy(src.getConfig(), true);
        } finally {
            rgba.release();
            gray.release();
            thresh.release();
        }
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
