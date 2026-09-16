package com.anscanner.app.processing;

import android.graphics.Bitmap;
import android.util.Log;

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
    // Edge Detection
    // ════════════════════════════════════════════════════════════════════

    /**
     * Detects document edges in the given bitmap.
     *
     * <p>Pipeline: RGBA → Gray → GaussianBlur → Canny → findContours
     * → largest 4-point contour.</p>
     *
     * @param src Source bitmap (not mutated).
     * @return Array of 4 corner {@link Point}s in order:
     *         [top-left, top-right, bottom-right, bottom-left],
     *         or {@code null} if no document boundary is found.
     */
    public static Point[] detectDocumentEdges(Bitmap src) {
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat edges = new Mat();

        try {
            Utils.bitmapToMat(src, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            Imgproc.Canny(blurred, edges, 75, 200);

            // Dilate edges to close gaps in contour boundaries
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.dilate(edges, edges, kernel);
            kernel.release();

            return DocumentDetector.findDocumentContour(edges, src.getWidth(), src.getHeight());

        } catch (Exception e) {
            Log.e(TAG, "Edge detection failed", e);
            return null;
        } finally {
            rgba.release();
            gray.release();
            blurred.release();
            edges.release();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Real-time Frame Analysis (for live preview edge detection)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Detects document edges from a YUV_420_888 frame provided as a
     * grayscale byte array. Designed for real-time CameraX ImageAnalysis.
     *
     * <p>This method creates Mats from raw byte data, runs the full
     * edge detection pipeline, and <strong>strictly releases all Mats</strong>
     * before returning to prevent memory leaks during continuous analysis.</p>
     *
     * @param yuvData     Grayscale (Y-plane) byte array from ImageProxy.
     * @param width       Frame width in pixels.
     * @param height      Frame height in pixels.
     * @param rowStride   Row stride of the Y plane.
     * @return Ordered corners [TL, TR, BR, BL] or null if no document found.
     */
    public static Point[] detectDocumentEdgesFromFrame(
            byte[] yuvData, int width, int height, int rowStride) {

        Mat gray = null;
        Mat blurred = null;
        Mat edges = null;
        Mat kernel = null;

        try {
            // Create a Mat from the raw Y-plane bytes
            // rowStride may be larger than width, so we create from the stride
            gray = new Mat(height, rowStride, CvType.CV_8UC1);
            gray.put(0, 0, yuvData);

            // Crop to actual width if rowStride > width
            if (rowStride > width) {
                Mat cropped = gray.submat(0, height, 0, width);
                gray.release();
                gray = cropped;
            }

            // Blur to remove noise and paper texture
            blurred = new Mat();
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);

            // Canny edge detection
            edges = new Mat();
            Imgproc.Canny(blurred, edges, 50, 150);

            // Dilate to close gaps in contour boundaries
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.dilate(edges, edges, kernel);

            // Find the document contour
            return DocumentDetector.findDocumentContour(edges, width, height);

        } catch (Exception e) {
            Log.e(TAG, "Real-time edge detection failed", e);
            return null;
        } finally {
            if (gray != null) gray.release();
            if (blurred != null) blurred.release();
            if (edges != null) edges.release();
            if (kernel != null) kernel.release();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Perspective Warp (Crop & Correct)
    // ════════════════════════════════════════════════════════════════════

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
}
