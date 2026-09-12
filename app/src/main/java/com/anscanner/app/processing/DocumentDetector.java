package com.anscanner.app.processing;

import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Analyzes edge-detected images to find document contours.
 *
 * <p>Given a Canny edge output, this class finds the largest quadrilateral
 * contour that could represent a document boundary. The corner points are
 * returned in a consistent order: [top-left, top-right, bottom-right, bottom-left].</p>
 *
 * <p>If no suitable quadrilateral is found, returns the full image bounds
 * as fallback corners so the user can still proceed with manual cropping.</p>
 */
public final class DocumentDetector {

    private static final String TAG = "DocumentDetector";

    /**
     * Minimum contour area as a fraction of the total image area.
     * Contours smaller than this are discarded as noise.
     */
    private static final double MIN_AREA_RATIO = 0.1;

    /**
     * Maximum contour area as a fraction of the total image area.
     */
    private static final double MAX_AREA_RATIO = 0.95;

    /**
     * Epsilon factor for polygon approximation.
     * Lower values require the contour to more closely match a polygon.
     */
    private static final double EPSILON_FACTOR = 0.02;

    private DocumentDetector() {
        // Static utility class
    }

    /**
     * Finds the largest 4-point document contour in a Canny edge map.
     *
     * @param edges      Single-channel Canny edge Mat.
     * @param imgWidth   Original image width (for fallback bounds).
     * @param imgHeight  Original image height (for fallback bounds).
     * @return Ordered corners [TL, TR, BR, BL], or fallback full-image corners.
     */
    public static Point[] findDocumentContour(Mat edges, int imgWidth, int imgHeight) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();

        try {
            // Find all external contours
            Imgproc.findContours(edges, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            double totalArea = (double) imgWidth * imgHeight;
            double bestArea = 0;
            Point[] bestCorners = null;

            // Sort contours by area (largest first) for early exit
            Collections.sort(contours, (a, b) -> {
                double areaA = Imgproc.contourArea(a);
                double areaB = Imgproc.contourArea(b);
                return Double.compare(areaB, areaA);
            });

            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                double areaRatio = area / totalArea;

                // Skip contours that are too small or too large
                if (areaRatio < MIN_AREA_RATIO || areaRatio > MAX_AREA_RATIO) {
                    continue;
                }

                // Approximate the contour to a polygon
                MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
                double peri = Imgproc.arcLength(contour2f, true);
                MatOfPoint2f approx = new MatOfPoint2f();
                Imgproc.approxPolyDP(contour2f, approx, EPSILON_FACTOR * peri, true);

                Point[] points = approx.toArray();

                // We want exactly 4 vertices (quadrilateral = document)
                if (points.length == 4 && area > bestArea) {
                    // Verify the quadrilateral is convex
                    if (Imgproc.isContourConvex(new MatOfPoint(points))) {
                        bestArea = area;
                        bestCorners = orderCorners(points);
                    }
                }

                contour2f.release();
                approx.release();
            }

            if (bestCorners != null) {
                Log.i(TAG, "Document contour found (area ratio: " +
                        String.format("%.2f", bestArea / totalArea) + ")");
                return bestCorners;
            }

            // Fallback: return full image bounds with a small margin
            Log.i(TAG, "No document contour found, returning full image bounds");
            return getDefaultCorners(imgWidth, imgHeight);

        } finally {
            hierarchy.release();
            for (MatOfPoint contour : contours) {
                contour.release();
            }
        }
    }

    /**
     * Orders 4 corner points in consistent order:
     * [top-left, top-right, bottom-right, bottom-left].
     *
     * <p>Algorithm: sort by sum (x+y) for TL/BR diagonal,
     * sort by difference (y-x) for TR/BL diagonal.</p>
     */
    public static Point[] orderCorners(Point[] pts) {
        if (pts.length != 4) {
            throw new IllegalArgumentException("Exactly 4 points required, got " + pts.length);
        }

        Point[] ordered = new Point[4];

        // Calculate sum and difference for each point
        double[] sums = new double[4];
        double[] diffs = new double[4];
        for (int i = 0; i < 4; i++) {
            sums[i] = pts[i].x + pts[i].y;
            diffs[i] = pts[i].y - pts[i].x;
        }

        // Top-left has smallest sum (closest to origin)
        int tlIdx = indexOfMin(sums);
        ordered[0] = pts[tlIdx];

        // Bottom-right has largest sum (farthest from origin)
        int brIdx = indexOfMax(sums);
        ordered[2] = pts[brIdx];

        // Top-right has smallest difference (y - x is most negative)
        int trIdx = indexOfMin(diffs);
        ordered[1] = pts[trIdx];

        // Bottom-left has largest difference (y - x is most positive)
        int blIdx = indexOfMax(diffs);
        ordered[3] = pts[blIdx];

        return ordered;
    }

    /**
     * Returns default corner points representing the full image with a 5% margin.
     */
    public static Point[] getDefaultCorners(int width, int height) {
        double marginX = width * 0.05;
        double marginY = height * 0.05;
        return new Point[]{
                new Point(marginX, marginY),                          // Top-left
                new Point(width - marginX, marginY),                  // Top-right
                new Point(width - marginX, height - marginY),         // Bottom-right
                new Point(marginX, height - marginY)                  // Bottom-left
        };
    }

    // ── Private Helpers ──────────────────────────────────────────────────

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
