package com.anscanner.app.model.draft;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.ui.custom.AnnotationDrawingView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Non-destructive scan edit state model.
 * Stores normalized crop points (8 floats: x1,y1 to x4,y4), rotation degrees,
 * filter mode, vector annotations, and timestamp without duplicating raw bitmaps in memory.
 */
public final class ScanEditState implements Serializable {
    private static final long serialVersionUID = 2L;

    public static final String FILTER_ORIGINAL = "ORIGINAL";

    @Nullable
    private final float[] normalizedCropPoints;
    private final int rotationDegrees;
    @NonNull
    private final String filterMode;
    private final long timestamp;
    @Nullable
    private final transient AnnotationDrawingView.AnnotationState annotations;

    public ScanEditState(@Nullable float[] normalizedCropPoints,
                         int rotationDegrees,
                         @Nullable String filterMode,
                         long timestamp) {
        this(normalizedCropPoints, rotationDegrees, filterMode, timestamp, null);
    }

    public ScanEditState(@Nullable float[] normalizedCropPoints,
                         int rotationDegrees,
                         @Nullable String filterMode,
                         long timestamp,
                         @Nullable AnnotationDrawingView.AnnotationState annotations) {
        this.normalizedCropPoints = normalizedCropPoints != null ? normalizedCropPoints.clone() : null;
        this.rotationDegrees = ((rotationDegrees % 360) + 360) % 360;
        this.filterMode = filterMode != null ? filterMode : FILTER_ORIGINAL;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        this.annotations = annotations != null ? annotations.copy() : null;
    }

    public static ScanEditState createDefault() {
        return new ScanEditState(null, 0, FILTER_ORIGINAL, System.currentTimeMillis(), null);
    }

    @Nullable
    public float[] getNormalizedCropPoints() {
        return normalizedCropPoints != null ? normalizedCropPoints.clone() : null;
    }

    public int getRotationDegrees() {
        return rotationDegrees;
    }

    @NonNull
    public String getFilterMode() {
        return filterMode;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Nullable
    public AnnotationDrawingView.AnnotationState getAnnotations() {
        return annotations != null ? annotations.copy() : null;
    }

    public boolean hasEdits() {
        return normalizedCropPoints != null || rotationDegrees != 0 || !FILTER_ORIGINAL.equals(filterMode)
                || (annotations != null && !annotations.isEmpty());
    }

    public ScanEditState withCrop(@Nullable float[] newNormalizedCropPoints) {
        return new ScanEditState(newNormalizedCropPoints, this.rotationDegrees, this.filterMode, System.currentTimeMillis(), this.annotations);
    }

    public ScanEditState withCrop(@Nullable float[] newNormalizedCropPoints, @Nullable AnnotationDrawingView.AnnotationState newAnnotations) {
        return new ScanEditState(newNormalizedCropPoints, this.rotationDegrees, this.filterMode, System.currentTimeMillis(), newAnnotations);
    }

    public ScanEditState withRotation(int newRotationDegrees) {
        return new ScanEditState(this.normalizedCropPoints, newRotationDegrees, this.filterMode, System.currentTimeMillis(), this.annotations);
    }

    public ScanEditState withRotation(int newRotationDegrees, @Nullable AnnotationDrawingView.AnnotationState newAnnotations) {
        return new ScanEditState(this.normalizedCropPoints, newRotationDegrees, this.filterMode, System.currentTimeMillis(), newAnnotations);
    }

    public ScanEditState withFilter(@Nullable String newFilterMode) {
        return new ScanEditState(this.normalizedCropPoints, this.rotationDegrees, newFilterMode, System.currentTimeMillis(), this.annotations);
    }

    public ScanEditState withAnnotations(@Nullable AnnotationDrawingView.AnnotationState newAnnotations) {
        return new ScanEditState(this.normalizedCropPoints, this.rotationDegrees, this.filterMode, System.currentTimeMillis(), newAnnotations);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (normalizedCropPoints != null) {
            JSONArray arr = new JSONArray();
            for (float p : normalizedCropPoints) {
                arr.put((double) p);
            }
            json.put("cropPoints", arr);
        }
        json.put("rotation", rotationDegrees);
        json.put("filter", filterMode);
        json.put("timestamp", timestamp);
        if (annotations != null && !annotations.isEmpty()) {
            json.put("annotations", annotations.toJson());
        }
        return json;
    }

    public static ScanEditState fromJson(JSONObject json) throws JSONException {
        if (json == null) return createDefault();
        float[] cropPoints = null;
        if (json.has("cropPoints")) {
            JSONArray arr = json.getJSONArray("cropPoints");
            cropPoints = new float[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                cropPoints[i] = (float) arr.getDouble(i);
            }
        }
        int rotation = json.optInt("rotation", 0);
        String filter = json.optString("filter", FILTER_ORIGINAL);
        long timestamp = json.optLong("timestamp", System.currentTimeMillis());
        AnnotationDrawingView.AnnotationState annos = null;
        if (json.has("annotations")) {
            annos = AnnotationDrawingView.AnnotationState.fromJson(json.getJSONObject("annotations"));
        }
        return new ScanEditState(cropPoints, rotation, filter, timestamp, annos);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScanEditState that = (ScanEditState) o;
        return rotationDegrees == that.rotationDegrees &&
                filterMode.equals(that.filterMode) &&
                Arrays.equals(normalizedCropPoints, that.normalizedCropPoints) &&
                areAnnotationsEqual(this.annotations, that.annotations);
    }

    private static boolean areAnnotationsEqual(@Nullable AnnotationDrawingView.AnnotationState a,
                                                @Nullable AnnotationDrawingView.AnnotationState b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(normalizedCropPoints);
        result = 31 * result + rotationDegrees;
        result = 31 * result + filterMode.hashCode();
        if (annotations != null) {
            result = 31 * result + annotations.hashCode();
        }
        return result;
    }

    @NonNull
    @Override
    public String toString() {
        return "ScanEditState{" +
                "cropPoints=" + (normalizedCropPoints != null ? Arrays.toString(normalizedCropPoints) : "null") +
                ", rotationDegrees=" + rotationDegrees +
                ", filterMode='" + filterMode + '\'' +
                ", annotations=" + (annotations != null ? annotations.size() : 0) +
                ", timestamp=" + timestamp +
                '}';
    }
}
