package com.anscanner.app.processing;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hybrid On-Device Document Corner Detector combining:
 * 1. TensorFlow Lite Deep Learning localization (using scanner_model.tflite).
 * 2. OpenCV Canny edge + morphological closing + contour detection.
 *
 * <p><strong>Safeguards & Geometry Rules:</strong></p>
 * <ul>
 *   <li>Morphological closing (5x5 kernel) + dilate (3x3 kernel) to bridge broken edges.</li>
 *   <li>Minimum area requirement: 5% of total frame area (supports smaller docs / distance).</li>
 *   <li>Aspect ratio safeguard: bounding box aspect ratio &lt; 2.5f (rejects narrow vertical strips/cords).</li>
 *   <li>4 vertices: approxPolyDP with epsilon = 0.02 * perimeter strictly requires exactly 4 points.</li>
 *   <li>No convexity constraint: Convexity check removed so curled/bent paper passes validation.</li>
 *   <li>Fallback UI: Returns null if no document is detected so the live camera overlay smoothly clears.</li>
 * </ul>
 */
public class DocumentDetector {

    private static final String TAG = "DocumentDetector";
    public static final String MODEL_FILE_NAME = "scanner_model.tflite";

    private static volatile DocumentDetector instance;

    private Interpreter interpreter;
    private boolean isModelLoaded = false;
    private int inputWidth = 256;
    private int inputHeight = 256;
    private boolean isNCHW = false;
    private int[] outputShape;

    public static DocumentDetector getInstance(Context context) {
        if (instance == null) {
            synchronized (DocumentDetector.class) {
                if (instance == null) {
                    instance = new DocumentDetector(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public DocumentDetector(Context context) {
        try {
            MappedByteBuffer modelBuffer = loadModelFile(context, MODEL_FILE_NAME);
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            this.interpreter = new Interpreter(modelBuffer, options);

            int[] inShape = interpreter.getInputTensor(0).shape();
            if (inShape != null && inShape.length == 4) {
                if (inShape[1] == 3) {
                    isNCHW = true;
                    inputHeight = inShape[2];
                    inputWidth = inShape[3];
                } else {
                    isNCHW = false;
                    inputHeight = inShape[1];
                    inputWidth = inShape[2];
                }
            }
            outputShape = interpreter.getOutputTensor(0).shape();
            this.isModelLoaded = true;
            Log.i(TAG, "TFLite Document Corner Detector initialized with " + MODEL_FILE_NAME +
                    ", input: [" + inputWidth + "x" + inputHeight + (isNCHW ? ", NCHW]" : ", NHWC]"));
        } catch (Exception e) {
            Log.w(TAG, "TFLite model not loaded or error initializing (" + e.getMessage() + "). Using OpenCV contour detection.", e);
            this.isModelLoaded = false;
        }
    }

    /**
     * Detects 4 document corners from an input bitmap.
     *
     * @param src Source bitmap (not mutated).
     * @return 4 ordered corner points [TL, TR, BR, BL], or null if no valid document is detected.
     */
    public Point[] detectCorners(Bitmap src) {
        if (src == null || src.isRecycled()) {
            return null;
        }

        try {
            // 1. Tier 1: On-Device TFLite Model Inference (if available & valid)
            if (isModelLoaded && interpreter != null) {
                Point[] modelCorners = runTFLiteInference(src);
                if (modelCorners != null && modelCorners.length == 4) {
                    return modelCorners;
                }
            }

            // 2. Tier 2: OpenCV Contour Detection with relaxed geometry
            Point[] openCvCorners = detectCornersOpenCv(src);
            if (openCvCorners != null && openCvCorners.length == 4) {
                return openCvCorners;
            }

            // 3. Fallback: return null so live camera overlay smoothly clears
            return null;

        } catch (Exception e) {
            Log.w(TAG, "Document detection exception caught", e);
            return null;
        }
    }

    /**
     * Executes TFLite inference on the source bitmap with aspect-ratio letterboxing.
     */
    private Point[] runTFLiteInference(Bitmap src) {
        if (!isModelLoaded || interpreter == null || src == null || src.isRecycled()) {
            return null;
        }

        int origWidth = src.getWidth();
        int origHeight = src.getHeight();

        Bitmap letterboxBitmap = null;
        try {
            float scale = Math.min((float) inputWidth / origWidth, (float) inputHeight / origHeight);
            int scaledW = Math.max(1, Math.round(origWidth * scale));
            int scaledH = Math.max(1, Math.round(origHeight * scale));
            float padX = (inputWidth - scaledW) / 2f;
            float padY = (inputHeight - scaledH) / 2f;

            letterboxBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(letterboxBitmap);
            canvas.drawColor(Color.BLACK);
            Bitmap scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true);
            canvas.drawBitmap(scaled, padX, padY, null);
            if (scaled != src) {
                scaled.recycle();
            }

            ByteBuffer inputBuffer = convertBitmapToByteBuffer(letterboxBitmap, inputWidth, inputHeight, isNCHW);

            Point[] detectedCorners = null;

            if (outputShape != null && outputShape.length == 3 && outputShape[1] == 4 && outputShape[2] == 2) {
                float[][][] outputArray = new float[1][4][2];
                synchronized (this) {
                    interpreter.run(inputBuffer, outputArray);
                }
                detectedCorners = new Point[4];
                for (int i = 0; i < 4; i++) {
                    float normX = outputArray[0][i][0];
                    float normY = outputArray[0][i][1];
                    if (normX <= 1.0f) normX *= inputWidth;
                    if (normY <= 1.0f) normY *= inputHeight;
                    float origX = Math.max(0f, Math.min((float) origWidth, (normX - padX) / scale));
                    float origY = Math.max(0f, Math.min((float) origHeight, (normY - padY) / scale));
                    detectedCorners[i] = new Point(origX, origY);
                }
            } else if (outputShape != null && outputShape.length == 2 && outputShape[1] >= 8) {
                float[][] outputArray = new float[1][outputShape[1]];
                synchronized (this) {
                    interpreter.run(inputBuffer, outputArray);
                }
                detectedCorners = new Point[4];
                for (int i = 0; i < 4; i++) {
                    float normX = outputArray[0][i * 2];
                    float normY = outputArray[0][i * 2 + 1];
                    if (normX <= 1.0f) normX *= inputWidth;
                    if (normY <= 1.0f) normY *= inputHeight;
                    float origX = Math.max(0f, Math.min((float) origWidth, (normX - padX) / scale));
                    float origY = Math.max(0f, Math.min((float) origHeight, (normY - padY) / scale));
                    detectedCorners[i] = new Point(origX, origY);
                }
            } else if (outputShape != null && outputShape.length == 3 && (outputShape[1] == 17 || outputShape[2] == 17)) {
                int dim1 = outputShape[1];
                int dim2 = outputShape[2];
                float[][][] outputArray = new float[1][dim1][dim2];
                synchronized (this) {
                    interpreter.run(inputBuffer, outputArray);
                }
                detectedCorners = parseYoloPoseOutput(outputArray[0], dim1, dim2, origWidth, origHeight, scale, padX, padY);
            }

            if (detectedCorners != null && isValidDocumentGeometry(detectedCorners, origWidth, origHeight)) {
                return orderCorners(detectedCorners);
            }

            return null;
        } catch (Exception e) {
            Log.w(TAG, "TFLite inference failed: " + e.getMessage());
            return null;
        } finally {
            if (letterboxBitmap != null && !letterboxBitmap.isRecycled()) {
                letterboxBitmap.recycle();
            }
        }
    }

    /**
     * Parses Ultralytics YOLO Pose / Keypoint output.
     */
    private Point[] parseYoloPoseOutput(float[][] output, int rows, int cols, int origWidth, int origHeight,
                                        float scale, float padX, float padY) {
        if (rows == 17 && cols > 0) {
            int bestAnchor = -1;
            float maxScore = -1f;

            for (int a = 0; a < cols; a++) {
                float score = output[4][a];
                if (score > maxScore) {
                    maxScore = score;
                    bestAnchor = a;
                }
            }

            if (bestAnchor >= 0 && maxScore >= 0.15f) {
                Point[] corners = new Point[4];
                boolean hasValidKeypoints = true;

                for (int k = 0; k < 4; k++) {
                    int xRow = 5 + (k * 3);
                    int yRow = 6 + (k * 3);
                    float kptX = output[xRow][bestAnchor];
                    float kptY = output[yRow][bestAnchor];

                    if (kptX <= 1.0f && kptY <= 1.0f) {
                        kptX *= inputWidth;
                        kptY *= inputHeight;
                    }

                    if (kptX <= 0.001f && kptY <= 0.001f) {
                        hasValidKeypoints = false;
                        break;
                    }

                    float origX = Math.max(0f, Math.min((float) origWidth, (kptX - padX) / scale));
                    float origY = Math.max(0f, Math.min((float) origHeight, (kptY - padY) / scale));
                    corners[k] = new Point(origX, origY);
                }

                if (hasValidKeypoints) {
                    return corners;
                }

                float cx = output[0][bestAnchor];
                float cy = output[1][bestAnchor];
                float w = output[2][bestAnchor];
                float h = output[3][bestAnchor];

                if (cx <= 1.0f && cy <= 1.0f) {
                    cx *= inputWidth;
                    cy *= inputHeight;
                    w *= inputWidth;
                    h *= inputHeight;
                }

                float modelLeft = cx - w / 2f;
                float modelTop = cy - h / 2f;
                float modelRight = cx + w / 2f;
                float modelBottom = cy + h / 2f;

                float left = Math.max(0f, Math.min((float) origWidth, (modelLeft - padX) / scale));
                float top = Math.max(0f, Math.min((float) origHeight, (modelTop - padY) / scale));
                float right = Math.max(0f, Math.min((float) origWidth, (modelRight - padX) / scale));
                float bottom = Math.max(0f, Math.min((float) origHeight, (modelBottom - padY) / scale));

                return new Point[]{
                        new Point(left, top),
                        new Point(right, top),
                        new Point(right, bottom),
                        new Point(left, bottom)
                };
            }
        }
        return null;
    }

    /**
     * Converts a scaled Bitmap into a direct ByteBuffer (native byte order)
     * normalized to floats between 0.0 and 1.0.
     */
    public static ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap, int inputW, int inputH, boolean nchw) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1 * 3 * inputW * inputH * 4);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[inputW * inputH];
        bitmap.getPixels(intValues, 0, inputW, 0, 0, inputW, inputH);

        if (nchw) {
            for (int pixel : intValues) {
                byteBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f);
            }
            for (int pixel : intValues) {
                byteBuffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);
            }
            for (int pixel : intValues) {
                byteBuffer.putFloat((pixel & 0xFF) / 255.0f);
            }
        } else {
            for (int pixel : intValues) {
                byteBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f);
                byteBuffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);
                byteBuffer.putFloat((pixel & 0xFF) / 255.0f);
            }
        }

        byteBuffer.rewind();
        return byteBuffer;
    }

    /**
     * Validates document geometry:
     * - Minimum area &gt; 5% of total image area.
     * - Bounding box aspect ratio &lt; 2.5f (anti-strip safeguard).
     * - No convexity requirement (paper curls/folds allowed).
     */
    public static boolean isValidDocumentGeometry(Point[] corners, int width, int height) {
        if (corners == null || corners.length != 4) return false;

        // Shoelace formula for area
        double area = 0.5 * Math.abs(
                (corners[0].x * corners[1].y - corners[1].x * corners[0].y) +
                (corners[1].x * corners[2].y - corners[2].x * corners[1].y) +
                (corners[2].x * corners[3].y - corners[3].x * corners[2].y) +
                (corners[3].x * corners[0].y - corners[0].x * corners[3].y)
        );

        // Lower Minimum Area to 5%
        double totalArea = (double) width * height;
        if (area <= totalArea * 0.05) {
            return false;
        }

        // Bounding box aspect ratio < 2.5
        double minX = Math.min(Math.min(corners[0].x, corners[1].x), Math.min(corners[2].x, corners[3].x));
        double maxX = Math.max(Math.max(corners[0].x, corners[1].x), Math.max(corners[2].x, corners[3].x));
        double minY = Math.min(Math.min(corners[0].y, corners[1].y), Math.min(corners[2].y, corners[3].y));
        double maxY = Math.max(Math.max(corners[0].y, corners[1].y), Math.max(corners[2].y, corners[3].y));

        double boxW = maxX - minX;
        double boxH = maxY - minY;
        if (boxW <= 0 || boxH <= 0) return false;

        double aspectRatio = Math.max(boxW, boxH) / Math.min(boxW, boxH);
        if (aspectRatio >= 2.5) {
            return false;
        }

        return true;
    }

    /**
     * OpenCV Canny edge + morphological closing + contour detection with relaxed geometry:
     * - Morphological closing (5x5 kernel) bridges broken edges.
     * - Dilate (3x3 kernel) solidifies boundary lines.
     * - Lower Minimum Area: &gt;= 5% of total frame area.
     * - Keep Aspect Ratio: &lt; 2.5 (rejects thin strips, lines, cords).
     * - Ensure 4 Points: exactly 4 points from approxPolyDP (epsilon = 0.02 * perimeter).
     * - Remove Convexity: NO convexity check (natural paper bending allowed).
     * - Fallback UI: returns null when no valid contour matches.
     */
    public static Point[] detectCornersOpenCv(Bitmap src) {
        if (src == null || src.isRecycled()) return null;

        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();

        try {
            Utils.bitmapToMat(src, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            Imgproc.Canny(blurred, edges, 75, 200);

            // 1. Add Morphological Closing to Bridge Broken Edges:
            Mat closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, closeKernel);
            closeKernel.release();

            // Follow with dilate to ensure boundary lines are solid and fully enclosed
            Mat dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.dilate(edges, edges, dilateKernel);
            dilateKernel.release();

            Imgproc.findContours(edges, contours, hierarchy,
                    Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

            int imgWidth = src.getWidth();
            int imgHeight = src.getHeight();
            double totalArea = (double) imgWidth * imgHeight;
            double minArea = totalArea * 0.05; // 5% minimum area
            double bestArea = 0;
            Point[] bestCorners = null;

            Collections.sort(contours, (a, b) -> Double.compare(Imgproc.contourArea(b), Imgproc.contourArea(a)));

            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);

                // Lower Minimum Area: 5%
                if (area <= minArea) {
                    continue;
                }

                // Keep Aspect Ratio: Retain anti-strip safeguard: aspectRatio < 2.5f
                Rect bounds = Imgproc.boundingRect(contour);
                if (bounds.width <= 0 || bounds.height <= 0) {
                    continue;
                }
                float aspectRatio = (float) Math.max(bounds.width, bounds.height) / (float) Math.min(bounds.width, bounds.height);
                if (aspectRatio >= 2.5f) {
                    continue; // Reject strips, lines, and cords
                }

                MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
                double peri = Imgproc.arcLength(contour2f, true);
                MatOfPoint2f approx = new MatOfPoint2f();
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true);

                Point[] points = approx.toArray();

                // Ensure 4 Points: approxCurve.total() == 4
                // Remove Convexity: NO convexity check
                if (points.length == 4 && area > bestArea) {
                    bestArea = area;
                    bestCorners = orderCorners(points);
                }

                contour2f.release();
                approx.release();
            }

            return bestCorners;

        } catch (Exception e) {
            Log.e(TAG, "OpenCV contour detection failed", e);
            return null;
        } finally {
            rgba.release();
            gray.release();
            blurred.release();
            edges.release();
            hierarchy.release();
            for (MatOfPoint c : contours) {
                c.release();
            }
        }
    }

    /**
     * Orders 4 corner points in consistent order:
     * [top-left, top-right, bottom-right, bottom-left].
     */
    public static Point[] orderCorners(Point[] pts) {
        if (pts == null || pts.length != 4) {
            throw new IllegalArgumentException("Exactly 4 points required, got " + (pts != null ? pts.length : 0));
        }

        Point[] ordered = new Point[4];
        double[] sums = new double[4];
        double[] diffs = new double[4];
        for (int i = 0; i < 4; i++) {
            sums[i] = pts[i].x + pts[i].y;
            diffs[i] = pts[i].y - pts[i].x;
        }

        // Top-left has smallest sum (closest to origin)
        ordered[0] = pts[indexOfMin(sums)];
        // Bottom-right has largest sum (farthest from origin)
        ordered[2] = pts[indexOfMax(sums)];
        // Top-right has smallest difference (y - x is most negative)
        ordered[1] = pts[indexOfMin(diffs)];
        // Bottom-left has largest difference (y - x is most positive)
        ordered[3] = pts[indexOfMax(diffs)];

        return ordered;
    }

    /**
     * Returns default corner points representing the full image with a 5% margin.
     * Used as manual crop handle fallback in CropPreviewActivity.
     */
    public static Point[] getDefaultCorners(int width, int height) {
        double marginX = width * 0.05;
        double marginY = height * 0.05;
        return new Point[]{
                new Point(marginX, marginY),
                new Point(width - marginX, marginY),
                new Point(width - marginX, height - marginY),
                new Point(marginX, height - marginY)
        };
    }

    private static MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelPath);
        try (FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    public synchronized void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
            isModelLoaded = false;
        }
    }

    private static int indexOfMin(double[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] < arr[idx]) idx = i;
        }
        return idx;
    }

    private static int indexOfMax(double[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[idx]) idx = i;
        }
        return idx;
    }
}
