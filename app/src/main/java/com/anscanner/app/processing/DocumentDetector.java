package com.anscanner.app.processing;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.core.Point;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * On-Device TensorFlow Lite Document Corner Detector.
 *
 * <p>Replaces traditional OpenCV Canny/findContours heuristic detection with
 * a deep learning localization model capable of detecting documents, receipts,
 * and ID cards against complex backgrounds.</p>
 *
 * <p><strong>Pipeline:</strong></p>
 * <ol>
 *   <li><strong>Preprocessing:</strong> Downscales input frame/bitmap to model input
 *       dimensions (256x256), normalizes RGB values to [0.0, 1.0].</li>
 *   <li><strong>Inference:</strong> Executes TFLite Interpreter to predict the 4 corner points.</li>
 *   <li><strong>Post-processing:</strong> Extracts 4 normalized coordinates, scales them
 *       back to original bitmap dimensions, and sorts them into [TL, TR, BR, BL] order.</li>
 * </ol>
 */
public class DocumentDetector {

    private static final String TAG = "DocumentDetector";
    public static final String MODEL_FILE_NAME = "document_corner_detector.tflite";
    public static final int INPUT_SIZE = 256;

    private static volatile DocumentDetector instance;

    private Interpreter interpreter;
    private boolean isModelLoaded = false;
    private final ImageProcessor imageProcessor;

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
        this.imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .add(new NormalizeOp(0.0f, 255.0f))
                .build();

        try {
            MappedByteBuffer modelBuffer = loadModelFile(context, MODEL_FILE_NAME);
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            this.interpreter = new Interpreter(modelBuffer, options);
            this.isModelLoaded = true;
            Log.i(TAG, "TFLite Document Corner Detector initialized successfully.");
        } catch (Exception e) {
            Log.w(TAG, "TFLite model not loaded or placeholder detected. Falling back to default corners.", e);
            this.isModelLoaded = false;
        }
    }

    /**
     * Detects 4 document corners from an input bitmap using TFLite.
     *
     * @param src Source bitmap (not mutated).
     * @return 4 ordered corner points [TL, TR, BR, BL] scaled to src dimensions.
     */
    public Point[] detectCorners(Bitmap src) {
        if (src == null || src.isRecycled()) {
            return null;
        }

        int origWidth = src.getWidth();
        int origHeight = src.getHeight();

        if (!isModelLoaded || interpreter == null) {
            return getDefaultCorners(origWidth, origHeight);
        }

        try {
            // 1. Preprocessing: convert Bitmap to TensorImage (256x256, normalized)
            TensorImage tensorImage = preprocess(src);

            // 2. Prepare output buffer matching model tensor shape (e.g. [1, 8] or [1, 4, 2])
            int[] outputShape = interpreter.getOutputTensor(0).shape();
            TensorBuffer outputBuffer = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32);

            // 3. Inference
            synchronized (this) {
                interpreter.run(tensorImage.getBuffer(), outputBuffer.getBuffer().rewind());
            }

            // 4. Post-processing
            float[] flatCoordinates = outputBuffer.getFloatArray();
            if (flatCoordinates != null && flatCoordinates.length >= 8) {
                Point[] detected = new Point[4];
                for (int i = 0; i < 4; i++) {
                    float normX = Math.max(0.0f, Math.min(1.0f, flatCoordinates[i * 2]));
                    float normY = Math.max(0.0f, Math.min(1.0f, flatCoordinates[i * 2 + 1]));
                    detected[i] = new Point(normX * origWidth, normY * origHeight);
                }
                return orderCorners(detected);
            }

        } catch (Exception e) {
            Log.e(TAG, "TFLite corner inference failed, using fallback bounds", e);
        }

        return getDefaultCorners(origWidth, origHeight);
    }

    /**
     * Preprocesses an input Bitmap into a normalized TensorImage.
     *
     * @param bitmap Input bitmap.
     * @return Processed TensorImage ready for TFLite inference.
     */
    public TensorImage preprocess(Bitmap bitmap) {
        TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
        tensorImage.load(bitmap);
        return imageProcessor.process(tensorImage);
    }

    /**
     * Converts a bitmap into a preprocessed ByteBuffer (useful for custom model pipelines).
     */
    public static ByteBuffer preprocessToByteBuffer(Bitmap bitmap, int targetW, int targetH) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1 * targetW * targetH * 3 * 4);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[targetW * targetH];
        resized.getPixels(intValues, 0, targetW, 0, 0, targetW, targetH);
        if (resized != bitmap) {
            resized.recycle();
        }

        for (int pixelValue : intValues) {
            float r = ((pixelValue >> 16) & 0xFF) / 255.0f;
            float g = ((pixelValue >> 8) & 0xFF) / 255.0f;
            float b = (pixelValue & 0xFF) / 255.0f;
            byteBuffer.putFloat(r);
            byteBuffer.putFloat(g);
            byteBuffer.putFloat(b);
        }
        byteBuffer.rewind();
        return byteBuffer;
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
