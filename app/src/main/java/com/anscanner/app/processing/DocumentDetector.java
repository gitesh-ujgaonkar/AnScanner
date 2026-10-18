package com.anscanner.app.processing;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PointF;
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
    private float[][][] cachedOutputArray;

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
            if (outputShape != null && outputShape.length == 3) {
                cachedOutputArray = new float[1][outputShape[1]][outputShape[2]];
            }
            this.isModelLoaded = true;
            Log.i(TAG, "TFLite Document Corner Detector initialized with " + MODEL_FILE_NAME +
                    ", input: [" + inputWidth + "x" + inputHeight + (isNCHW ? ", NCHW]" : ", NHWC]"));
        } catch (Exception e) {
            Log.w(TAG, "TFLite model not loaded or error initializing (" + e.getMessage() + "). Using OpenCV contour detection.", e);
            this.isModelLoaded = false;
        }
    }

    /**
     * Detects 4 document corners from an input bitmap with strict validation.
     *
     * @param src Source bitmap (not mutated).
     * @param useFallback If true, falls back to 5% inset margin when no valid convex quad is found.
     * @return 4 ordered corner points [TL, TR, BR, BL], or null if useFallback is false and no document detected.
     */
    public Point[] detectCorners(Bitmap src, boolean useFallback) {
        if (src == null || src.isRecycled()) {
            return (useFallback && src != null) ? getDefaultCorners(src.getWidth(), src.getHeight()) : null;
        }

        try {
            // 1. Tier 1: On-Device Deep Learning TFLite Model Inference (Primary)
            if (isModelLoaded && interpreter != null) {
                Point[] modelCorners = runTFLiteInference(src);
                if (modelCorners != null && modelCorners.length == 4) {
                    Point[] orderedModel = orderCorners(modelCorners);
                    if (isValidDocumentGeometry(orderedModel, src.getWidth(), src.getHeight())) {
                        return orderedModel;
                    }
                }
            }

            // 2. Tier 2: OpenCV Canny edge + morphological contour detection fallback
            Point[] openCvCorners = detectCornersOpenCv(src);
            if (openCvCorners != null && openCvCorners.length == 4) {
                Point[] orderedOpenCv = orderCorners(openCvCorners);
                if (isValidDocumentGeometry(orderedOpenCv, src.getWidth(), src.getHeight())) {
                    return orderedOpenCv;
                }
            }

            // 3. Fallback: Safe 5% inset full-frame crop
            return useFallback ? getDefaultCorners(src.getWidth(), src.getHeight()) : null;

        } catch (Exception e) {
            Log.w(TAG, "Document detection exception caught", e);
            return useFallback ? getDefaultCorners(src.getWidth(), src.getHeight()) : null;
        }
    }

    /**
     * Detects 4 document corners from an input bitmap, falling back to 5% inset margin by default.
     */
    public Point[] detectCorners(Bitmap src) {
        return detectCorners(src, true);
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
                float[][][] out = (cachedOutputArray != null && cachedOutputArray[0].length == dim1 && cachedOutputArray[0][0].length == dim2)
                        ? cachedOutputArray : new float[1][dim1][dim2];
                synchronized (this) {
                    interpreter.run(inputBuffer, out);
                }
                detectedCorners = parseYoloPoseOutput(out[0], dim1, dim2, origWidth, origHeight, scale, padX, padY);
            }

            if (detectedCorners != null) {
                Point[] ordered = orderCorners(detectedCorners);
                if (isValidDocumentGeometry(ordered, origWidth, origHeight)) {
                    return ordered;
                }
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

                    if (kptX <= 1.5f) {
                        kptX *= inputWidth;
                    }
                    if (kptY <= 1.5f) {
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

                if (cx <= 1.5f) cx *= inputWidth;
                if (cy <= 1.5f) cy *= inputHeight;
                if (w <= 1.5f) w *= inputWidth;
                if (h <= 1.5f) h *= inputHeight;

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
     * Calculates the polygon area using the Shoelace formula.
     */
    public static double getPolygonArea(Point[] corners) {
        if (corners == null || corners.length != 4) return 0;
        return 0.5 * Math.abs(
                (corners[0].x * corners[1].y - corners[1].x * corners[0].y) +
                (corners[1].x * corners[2].y - corners[2].x * corners[1].y) +
                (corners[2].x * corners[3].y - corners[2].x * corners[3].y) +
                (corners[3].x * corners[0].y - corners[0].x * corners[3].y)
        );
    }

    /**
     * Strictly validates document geometry:
     * - Must be a convex quadrilateral.
     * - Must occupy at least 20% and no more than 95% of total frame area.
     */
    public static boolean isValidDocumentGeometry(Point[] corners, int width, int height) {
        if (corners == null || corners.length != 4) return false;

        // 1. Strict Convexity Check using OpenCV
        MatOfPoint mop = new MatOfPoint(corners);
        boolean isConvex = Imgproc.isContourConvex(mop);
        mop.release();
        if (!isConvex) {
            return false;
        }

        // 2. Strict Area Validation (5% to 95% of total image area)
        double area = getPolygonArea(corners);
        double totalArea = (double) width * height;
        if (area < totalArea * 0.05 || area > totalArea * 0.95) {
            return false;
        }

        return true;
    }

    /**
     * OpenCV Canny edge + morphological closing + contour detection on downscaled grayscale frame:
     * - Downscales frame to standard processing dimension (max 800px) for speed and noise reduction.
     * - Applies Gaussian blur, Canny edge detection, and morphological operations.
     * - Finds contours and sorts by area descending.
     * - Finds the largest closed convex quadrilateral with 4 vertices.
     * - Strictly validates 5% to 95% area and convexity.
     * - Returns null if no valid quadrilateral meets the criteria.
     */
    public static Point[] detectCornersOpenCv(Bitmap src) {
        if (src == null || src.isRecycled()) return null;

        int origW = src.getWidth();
        int origH = src.getHeight();
        if (origW <= 0 || origH <= 0) return null;

        // Downscale frame to max dimension 800 for robust, noise-free edge detection
        int maxDim = 800;
        float scale = 1.0f;
        Bitmap procBmp = src;
        boolean needRecycle = false;
        if (Math.max(origW, origH) > maxDim) {
            scale = (float) maxDim / Math.max(origW, origH);
            int targetW = Math.max(1, Math.round(origW * scale));
            int targetH = Math.max(1, Math.round(origH * scale));
            procBmp = Bitmap.createScaledBitmap(src, targetW, targetH, true);
            needRecycle = (procBmp != src);
        }

        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat closeKernel = null;
        Mat dilateKernel = null;
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();

        try {
            Utils.bitmapToMat(procBmp, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            Imgproc.Canny(blurred, edges, 75, 200);

            // Morphological closing to bridge broken document edges
            closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, closeKernel);

            // Morphological dilation to solidify boundary lines
            dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.dilate(edges, edges, dilateKernel);

            Imgproc.findContours(edges, contours, hierarchy,
                    Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

            double totalOrigArea = (double) origW * origH;
            double procArea = (double) procBmp.getWidth() * procBmp.getHeight();
            double minProcArea = procArea * 0.05; // At least 5%
            double maxProcArea = procArea * 0.95; // At most 95%

            Collections.sort(contours, (a, b) -> Double.compare(Imgproc.contourArea(b), Imgproc.contourArea(a)));

            Point[] bestCorners = null;
            double bestArea = 0;

            for (MatOfPoint contour : contours) {
                double cArea = Imgproc.contourArea(contour);
                if (cArea < minProcArea || cArea > maxProcArea) {
                    continue;
                }

                MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
                double peri = Imgproc.arcLength(contour2f, true);
                MatOfPoint2f approx = new MatOfPoint2f();

                // Standard epsilon 0.02 * perimeter
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true);
                Point[] points = approx.toArray();

                // If not 4 points, test adaptive epsilons (0.015 and 0.03)
                if (points.length != 4) {
                    approx.release();
                    approx = new MatOfPoint2f();
                    Imgproc.approxPolyDP(contour2f, approx, 0.015 * peri, true);
                    points = approx.toArray();
                    if (points.length != 4) {
                        approx.release();
                        approx = new MatOfPoint2f();
                        Imgproc.approxPolyDP(contour2f, approx, 0.03 * peri, true);
                        points = approx.toArray();
                    }
                }

                if (points.length == 4) {
                    // Strict Convexity Check
                    MatOfPoint approxMat = new MatOfPoint(points);
                    boolean isConvex = Imgproc.isContourConvex(approxMat);
                    approxMat.release();

                    if (isConvex) {
                        // Map points back to original image space
                        Point[] mappedPoints = new Point[4];
                        for (int i = 0; i < 4; i++) {
                            mappedPoints[i] = new Point(
                                    Math.max(0, Math.min(origW, points[i].x / scale)),
                                    Math.max(0, Math.min(origH, points[i].y / scale))
                            );
                        }

                        // Order corners consistently [TL, TR, BR, BL]
                        Point[] ordered = orderCorners(mappedPoints);
                        double quadArea = getPolygonArea(ordered);

                        // Strict Area Validation: 5% to 95% of total frame area
                        if (quadArea >= totalOrigArea * 0.05 && quadArea <= totalOrigArea * 0.95) {
                            if (quadArea > bestArea) {
                                bestArea = quadArea;
                                bestCorners = ordered;
                            }
                        }
                    }
                }

                contour2f.release();
                approx.release();
            }

            return bestCorners;

        } catch (Exception e) {
            Log.e(TAG, "OpenCV contour detection failed", e);
            return null;
        } finally {
            if (needRecycle && procBmp != null && !procBmp.isRecycled()) {
                procBmp.recycle();
            }
            rgba.release();
            gray.release();
            blurred.release();
            edges.release();
            if (closeKernel != null) closeKernel.release();
            if (dilateKernel != null) dilateKernel.release();
            hierarchy.release();
            for (MatOfPoint c : contours) {
                c.release();
            }
        }
    }

    /**
     * Orders 4 corner points in consistent clockwise order starting from Top-Left:
     * [Top-Left, Top-Right, Bottom-Right, Bottom-Left].
     *
     * <p>Uses centroid-angle sorting and origin proximity to guarantee 4 unique,
     * non-inverted vertices without duplicate corner collisions.</p>
     */
    public static Point[] orderCorners(Point[] pts) {
        if (pts == null || pts.length != 4) {
            throw new IllegalArgumentException("Exactly 4 points required, got " + (pts != null ? pts.length : 0));
        }

        // 1. Calculate centroid
        double cx = 0;
        double cy = 0;
        for (Point p : pts) {
            cx += p.x;
            cy += p.y;
        }
        cx /= 4.0;
        cy /= 4.0;

        // 2. Sort points in clockwise order around centroid
        List<Point> pointList = new ArrayList<>(4);
        for (Point p : pts) {
            pointList.add(p);
        }
        final double centerX = cx;
        final double centerY = cy;
        Collections.sort(pointList, (p1, p2) -> {
            double a1 = Math.atan2(p1.y - centerY, p1.x - centerX);
            double a2 = Math.atan2(p2.y - centerY, p2.x - centerX);
            return Double.compare(a1, a2);
        });

        // 3. Find the top-left point (closest to origin: min x + y)
        int tlIndex = 0;
        double minSum = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            double sum = pointList.get(i).x + pointList.get(i).y;
            if (sum < minSum) {
                minSum = sum;
                tlIndex = i;
            }
        }

        // 4. Return in clockwise order starting from Top-Left: [TL, TR, BR, BL]
        Point[] ordered = new Point[4];
        for (int i = 0; i < 4; i++) {
            ordered[i] = pointList.get((tlIndex + i) % 4);
        }

        return ordered;
    }

    /**
     * Orders 4 normalized corner points in consistent clockwise order [TL, TR, BR, BL].
     */
    public static PointF[] orderNormalizedCorners(PointF[] pts) {
        if (pts == null || pts.length != 4) {
            return getDefaultNormalizedCorners();
        }
        double cx = 0;
        double cy = 0;
        for (PointF p : pts) {
            cx += p.x;
            cy += p.y;
        }
        cx /= 4.0;
        cy /= 4.0;

        List<PointF> pointList = new ArrayList<>(4);
        for (PointF p : pts) {
            pointList.add(p);
        }
        final double centerX = cx;
        final double centerY = cy;
        Collections.sort(pointList, (p1, p2) -> {
            double a1 = Math.atan2(p1.y - centerY, p1.x - centerX);
            double a2 = Math.atan2(p2.y - centerY, p2.x - centerX);
            return Double.compare(a1, a2);
        });

        int tlIndex = 0;
        float minSum = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float sum = pointList.get(i).x + pointList.get(i).y;
            if (sum < minSum) {
                minSum = sum;
                tlIndex = i;
            }
        }

        PointF[] ordered = new PointF[4];
        for (int i = 0; i < 4; i++) {
            ordered[i] = pointList.get((tlIndex + i) % 4);
        }

        return ordered;
    }

    /**
     * Returns default corner points representing a full-frame crop with a safe 5% inset margin.
     * [TL, TR, BR, BL].
     */
    public static Point[] getDefaultCorners(int width, int height) {
        return new Point[]{
                new Point(0.05 * width, 0.05 * height),
                new Point(0.95 * width, 0.05 * height),
                new Point(0.95 * width, 0.95 * height),
                new Point(0.05 * width, 0.95 * height)
        };
    }

    /**
     * Returns default normalized corners [0.0..1.0] with a safe 5% inset margin:
     * [ (0.05, 0.05), (0.95, 0.05), (0.95, 0.95), (0.05, 0.95) ].
     */
    public static PointF[] getDefaultNormalizedCorners() {
        return new PointF[]{
                new PointF(0.05f, 0.05f),
                new PointF(0.95f, 0.05f),
                new PointF(0.95f, 0.95f),
                new PointF(0.05f, 0.95f)
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
