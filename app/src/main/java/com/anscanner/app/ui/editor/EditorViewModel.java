package com.anscanner.app.ui.editor;

import android.graphics.Bitmap;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

import com.anscanner.app.ui.editor.UnifiedEditorActivity.DocumentFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure Java Jetpack ViewModel retaining editor and crop state across configuration changes
 * (orientation rotation, screen resizing, multi-window mode).
 *
 * Persists:
 * - Active normalized polygon corner coordinates for real-time crop adjustments.
 * - Map of normalized corner points per document page.
 * - Current image URI, path, and active working bitmap.
 * - Selected document filter state and editing modes.
 */
public class EditorViewModel extends ViewModel {

    private static final String KEY_PAGE_INDEX = "editor_page_index";
    private static final String KEY_PAGE_PATHS = "editor_page_paths";
    private static final String KEY_ORIGINAL_PAGE_PATHS = "editor_original_page_paths";
    private static final String KEY_ACTIVE_POINTS = "editor_active_points";
    private static final String KEY_SELECTED_FILTER = "editor_selected_filter";
    private static final String KEY_IS_CROP_MODE = "editor_is_crop_mode";
    private static final String KEY_IS_DRAWING_MODE = "editor_is_drawing_mode";
    private static final String KEY_IS_FILTER_MODE = "editor_is_filter_mode";
    private static final String KEY_EDITOR_MODE = "editor_mode";
    private static final String KEY_IS_ADDING_PAGE = "editor_is_adding_page";
    private static final String KEY_IS_FROM_REVIEW = "editor_is_from_review";
    private static final String KEY_CURRENT_IMAGE_URI = "editor_current_image_uri";

    private final SavedStateHandle savedStateHandle;

    // In-memory normalized corners map: pageIndex -> float[8] (x0, y0, x1, y1, x2, y2, x3, y3)
    private final Map<Integer, float[]> normalizedCornersMap = new HashMap<>();

    // Transient cached Bitmap reference across configuration changes
    private Bitmap currentBitmap = null;

    public EditorViewModel(@NonNull SavedStateHandle savedStateHandle) {
        this.savedStateHandle = savedStateHandle;
    }

    public Map<Integer, float[]> getNormalizedCornersMap() {
        return normalizedCornersMap;
    }

    @Nullable
    public Bitmap getCurrentBitmap() {
        return currentBitmap;
    }

    public void setCurrentBitmap(@Nullable Bitmap bitmap) {
        this.currentBitmap = bitmap;
    }

    @Nullable
    public Uri getCurrentImageUri() {
        return savedStateHandle.get(KEY_CURRENT_IMAGE_URI);
    }

    public void setCurrentImageUri(@Nullable Uri uri) {
        savedStateHandle.set(KEY_CURRENT_IMAGE_URI, uri);
    }

    public int getPageIndex() {
        Integer idx = savedStateHandle.get(KEY_PAGE_INDEX);
        return idx != null ? idx : 0;
    }

    public void setPageIndex(int pageIndex) {
        savedStateHandle.set(KEY_PAGE_INDEX, pageIndex);
    }

    @Nullable
    public ArrayList<String> getPagePaths() {
        return savedStateHandle.get(KEY_PAGE_PATHS);
    }

    public void setPagePaths(@Nullable ArrayList<String> pagePaths) {
        savedStateHandle.set(KEY_PAGE_PATHS, pagePaths);
    }

    @Nullable
    public ArrayList<String> getOriginalPagePaths() {
        return savedStateHandle.get(KEY_ORIGINAL_PAGE_PATHS);
    }

    public void setOriginalPagePaths(@Nullable ArrayList<String> originalPagePaths) {
        savedStateHandle.set(KEY_ORIGINAL_PAGE_PATHS, originalPagePaths);
    }

    @Nullable
    public float[] getActivePoints() {
        return savedStateHandle.get(KEY_ACTIVE_POINTS);
    }

    public void setActivePoints(@Nullable float[] activePoints) {
        savedStateHandle.set(KEY_ACTIVE_POINTS, activePoints);
        if (activePoints != null) {
            normalizedCornersMap.put(getPageIndex(), activePoints);
        }
    }

    @NonNull
    public DocumentFilter getSelectedFilter() {
        String name = savedStateHandle.get(KEY_SELECTED_FILTER);
        if (name != null) {
            try {
                return DocumentFilter.valueOf(name);
            } catch (Exception e) {
                return DocumentFilter.ORIGINAL;
            }
        }
        return DocumentFilter.ORIGINAL;
    }

    public void setSelectedFilter(@NonNull DocumentFilter filter) {
        savedStateHandle.set(KEY_SELECTED_FILTER, filter.name());
    }

    public boolean isCropMode() {
        Boolean b = savedStateHandle.get(KEY_IS_CROP_MODE);
        return b != null ? b : false;
    }

    public void setCropMode(boolean cropMode) {
        savedStateHandle.set(KEY_IS_CROP_MODE, cropMode);
    }

    public boolean isDrawingMode() {
        Boolean b = savedStateHandle.get(KEY_IS_DRAWING_MODE);
        return b != null ? b : false;
    }

    public void setDrawingMode(boolean drawingMode) {
        savedStateHandle.set(KEY_IS_DRAWING_MODE, drawingMode);
    }

    public boolean isFilterMode() {
        Boolean b = savedStateHandle.get(KEY_IS_FILTER_MODE);
        return b != null ? b : false;
    }

    public void setFilterMode(boolean filterMode) {
        savedStateHandle.set(KEY_IS_FILTER_MODE, filterMode);
    }

    @Nullable
    public String getEditorMode() {
        return savedStateHandle.get(KEY_EDITOR_MODE);
    }

    public void setEditorMode(@Nullable String editorMode) {
        savedStateHandle.set(KEY_EDITOR_MODE, editorMode);
    }

    public boolean isAddingPage() {
        Boolean b = savedStateHandle.get(KEY_IS_ADDING_PAGE);
        return b != null ? b : false;
    }

    public void setAddingPage(boolean addingPage) {
        savedStateHandle.set(KEY_IS_ADDING_PAGE, addingPage);
    }

    public boolean isFromReview() {
        Boolean b = savedStateHandle.get(KEY_IS_FROM_REVIEW);
        return b != null ? b : false;
    }

    public void setFromReview(boolean fromReview) {
        savedStateHandle.set(KEY_IS_FROM_REVIEW, fromReview);
    }

    public void setNormalizedCornersForPage(int page, @NonNull float[] flat) {
        normalizedCornersMap.put(page, flat);
        if (page == getPageIndex()) {
            savedStateHandle.set(KEY_ACTIVE_POINTS, flat);
        }
    }

    @Nullable
    public float[] getNormalizedCornersForPage(int page) {
        float[] corners = normalizedCornersMap.get(page);
        if (corners != null) {
            return corners;
        }
        if (page == getPageIndex()) {
            return savedStateHandle.get(KEY_ACTIVE_POINTS);
        }
        return null;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        currentBitmap = null;
        normalizedCornersMap.clear();
    }
}
