package com.anscanner.app.model.draft;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.ui.custom.AnnotationDrawingView;
import com.anscanner.app.ui.editor.UnifiedEditorActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Auto-Save and Draft Restoration Manager for AnScanner.
 * Persists unsaved scan sessions non-destructively to context.getFilesDir() + "/drafts/"
 * with deep state preservation (raw base images, working copies, crop coordinates,
 * rotation angles, filters, full z-ordered annotations, and complete undo/redo history stacks).
 */
public final class DraftManager {
    private static final String TAG = "DraftManager";
    public static final String DRAFT_DIR_NAME = "drafts";
    public static final String DRAFT_INFO_FILE = "draft_info.json";
    public static final String EXTRA_RESUME_DRAFT = "extra_resume_draft";

    private static final ExecutorService DRAFT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile DraftManager sInstance;

    private final Context appContext;

    private DraftManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    @NonNull
    public static DraftManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (DraftManager.class) {
                if (sInstance == null) {
                    sInstance = new DraftManager(context);
                }
            }
        }
        return sInstance;
    }

    public boolean hasActiveDraft() {
        return hasActiveDraft(appContext);
    }

    public void clearDraft() {
        clearDraft(appContext);
    }

    public void clearDraftAsync(@Nullable Runnable onComplete) {
        clearDraftAsync(appContext, onComplete);
    }

    @Nullable
    public DraftSession loadDraft() {
        return loadDraft(appContext);
    }

    public static class DraftSession {
        @NonNull
        public final ArrayList<String> originalPagePaths;
        @NonNull
        public final ArrayList<String> pagePaths;
        @NonNull
        public final Map<Integer, EditHistoryManager> historyMap;
        public final int pageIndex;
        @NonNull
        public final String editorMode;
        public final long timestamp;

        public DraftSession(@NonNull ArrayList<String> originalPagePaths,
                            @NonNull ArrayList<String> pagePaths,
                            @NonNull Map<Integer, EditHistoryManager> historyMap,
                            int pageIndex,
                            @NonNull String editorMode,
                            long timestamp) {
            this.originalPagePaths = originalPagePaths;
            this.pagePaths = pagePaths;
            this.historyMap = historyMap;
            this.pageIndex = pageIndex;
            this.editorMode = editorMode;
            this.timestamp = timestamp;
        }
    }

    @NonNull
    public static File getDraftDir(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), DRAFT_DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Checks if a valid, restorable draft session exists.
     */
    public static boolean hasActiveDraft(@Nullable Context context) {
        if (context == null) return false;
        try {
            File draftDir = new File(context.getFilesDir(), DRAFT_DIR_NAME);
            if (!draftDir.exists() || !draftDir.isDirectory()) return false;

            File infoFile = new File(draftDir, DRAFT_INFO_FILE);
            if (!infoFile.exists() || infoFile.length() == 0) return false;

            DraftSession session = loadDraft(context);
            return session != null && !session.pagePaths.isEmpty();
        } catch (Exception e) {
            Log.e(TAG, "Error checking active draft", e);
            return false;
        }
    }

    /**
     * Asynchronously auto-saves the current editor session.
     */
    public static void saveDraftAsync(@NonNull Context context,
                                      @NonNull List<String> originalPaths,
                                      @NonNull List<String> renderedPaths,
                                      @NonNull Map<Integer, EditHistoryManager> historyMap,
                                      int pageIndex,
                                      @NonNull String editorMode) {
        final Context appContext = context.getApplicationContext();
        final ArrayList<String> origCopy = new ArrayList<>(originalPaths);
        final ArrayList<String> rendCopy = new ArrayList<>(renderedPaths);
        final Map<Integer, EditHistoryManager> histCopy = new HashMap<>(historyMap);

        DRAFT_EXECUTOR.execute(() -> saveDraftSync(appContext, origCopy, rendCopy, histCopy, pageIndex, editorMode));
    }

    /**
     * Synchronously saves the draft session.
     */
    public static synchronized boolean saveDraftSync(@NonNull Context context,
                                                     @NonNull List<String> originalPaths,
                                                     @NonNull List<String> renderedPaths,
                                                     @NonNull Map<Integer, EditHistoryManager> historyMap,
                                                     int pageIndex,
                                                     @NonNull String editorMode) {
        if (renderedPaths.isEmpty()) {
            clearDraft(context);
            return false;
        }

        try {
            File draftDir = getDraftDir(context);
            long now = System.currentTimeMillis();

            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("timestamp", now);
            root.put("pageIndex", Math.max(0, Math.min(pageIndex, renderedPaths.size() - 1)));
            root.put("editorMode", editorMode);

            JSONArray pagesArray = new JSONArray();

            for (int i = 0; i < renderedPaths.size(); i++) {
                String srcOrig = (i < originalPaths.size()) ? originalPaths.get(i) : renderedPaths.get(i);
                String srcRend = renderedPaths.get(i);

                File destOrigFile = new File(draftDir, "draft_raw_page_" + i + ".jpg");
                File destRendFile = new File(draftDir, "draft_rendered_page_" + i + ".jpg");

                copyFileIfNotSame(srcOrig, destOrigFile);
                copyFileIfNotSame(srcRend, destRendFile);

                JSONObject pageJson = new JSONObject();
                pageJson.put("originalPath", destOrigFile.getAbsolutePath());
                pageJson.put("renderedPath", destRendFile.getAbsolutePath());

                EditHistoryManager hm = historyMap.get(i);
                if (hm != null) {
                    pageJson.put("history", hm.toJson());
                    ScanEditState curr = hm.getCurrentState();
                    if (curr != null) {
                        pageJson.put("rotation", curr.getRotationDegrees());
                        pageJson.put("filter", curr.getFilterMode());
                        if (curr.getNormalizedCropPoints() != null) {
                            JSONArray arr = new JSONArray();
                            for (float p : curr.getNormalizedCropPoints()) {
                                arr.put((double) p);
                            }
                            pageJson.put("cropPoints", arr);
                        }
                        if (curr.getAnnotations() != null && !curr.getAnnotations().isEmpty()) {
                            pageJson.put("annotations", curr.getAnnotations().toJson());
                        }
                    }
                } else {
                    pageJson.put("history", new EditHistoryManager().toJson());
                }

                pagesArray.put(pageJson);
            }

            root.put("pages", pagesArray);

            // Atomic file write
            File tmpFile = new File(draftDir, DRAFT_INFO_FILE + ".tmp");
            try (FileWriter writer = new FileWriter(tmpFile)) {
                writer.write(root.toString());
            }

            File finalFile = new File(draftDir, DRAFT_INFO_FILE);
            if (finalFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                finalFile.delete();
            }
            if (!tmpFile.renameTo(finalFile)) {
                // Fallback direct copy if renameTo fails
                copyFile(tmpFile, finalFile);
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
            }

            Log.d(TAG, "Draft session successfully saved with " + renderedPaths.size() + " pages");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save draft session", e);
            return false;
        }
    }

    /**
     * Loads the stored draft session from disk.
     */
    @Nullable
    public static synchronized DraftSession loadDraft(@NonNull Context context) {
        try {
            File draftDir = new File(context.getFilesDir(), DRAFT_DIR_NAME);
            File infoFile = new File(draftDir, DRAFT_INFO_FILE);
            if (!infoFile.exists()) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(infoFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject root = new JSONObject(sb.toString());
            int pageIndex = root.optInt("pageIndex", 0);
            String editorMode = root.optString("editorMode", UnifiedEditorActivity.MODE_SINGLE_SCAN);
            long timestamp = root.optLong("timestamp", System.currentTimeMillis());

            JSONArray pagesArray = root.getJSONArray("pages");
            ArrayList<String> originalPaths = new ArrayList<>();
            ArrayList<String> renderedPaths = new ArrayList<>();
            Map<Integer, EditHistoryManager> historyMap = new HashMap<>();

            for (int i = 0; i < pagesArray.length(); i++) {
                JSONObject pageJson = pagesArray.getJSONObject(i);
                String origPath = pageJson.getString("originalPath");
                String rendPath = pageJson.getString("renderedPath");

                // Validate that the files actually exist on disk
                if (!new File(origPath).exists() && !new File(rendPath).exists()) {
                    Log.w(TAG, "Draft page file missing on disk: " + origPath);
                    clearDraft(context);
                    return null;
                }

                originalPaths.add(origPath);
                renderedPaths.add(rendPath);

                EditHistoryManager hm = null;
                if (pageJson.has("history")) {
                    try {
                        hm = EditHistoryManager.fromJson(pageJson.getJSONObject("history"));
                    } catch (Exception e) {
                        Log.w(TAG, "Failed parsing history, will fallback to top-level fields", e);
                    }
                }

                if (hm == null || hm.getCurrentState() == null) {
                    float[] cropPoints = null;
                    if (pageJson.has("cropPoints")) {
                        try {
                            JSONArray arr = pageJson.getJSONArray("cropPoints");
                            cropPoints = new float[arr.length()];
                            for (int k = 0; k < arr.length(); k++) {
                                cropPoints[k] = (float) arr.getDouble(k);
                            }
                        } catch (Exception ignored) {}
                    }
                    int rot = pageJson.optInt("rotation", 0);
                    String filter = pageJson.optString("filter", ScanEditState.FILTER_ORIGINAL);
                    AnnotationDrawingView.AnnotationState annos = null;
                    if (pageJson.has("annotations")) {
                        try {
                            annos = AnnotationDrawingView.AnnotationState.fromJson(pageJson.getJSONObject("annotations"));
                        } catch (Exception ignored) {}
                    }
                    ScanEditState s = new ScanEditState(cropPoints, rot, filter, timestamp, annos);
                    hm = new EditHistoryManager(s);
                }

                historyMap.put(i, hm);
            }

            if (renderedPaths.isEmpty()) {
                clearDraft(context);
                return null;
            }

            return new DraftSession(originalPaths, renderedPaths, historyMap, pageIndex, editorMode, timestamp);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load draft session", e);
            clearDraft(context);
            return null;
        }
    }

    /**
     * Discards and deletes all files in the draft directory.
     */
    public static synchronized void clearDraft(@Nullable Context context) {
        if (context == null) return;
        try {
            File draftDir = new File(context.getFilesDir(), DRAFT_DIR_NAME);
            if (draftDir.exists() && draftDir.isDirectory()) {
                File[] files = draftDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                draftDir.delete();
            }
            Log.d(TAG, "Draft session cleanly deleted");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing draft directory", e);
        }
    }

    /**
     * Asynchronously discards and deletes all files in the draft directory.
     */
    public static void clearDraftAsync(@Nullable Context context, @Nullable Runnable onComplete) {
        if (context == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        final Context appCtx = context.getApplicationContext();
        DRAFT_EXECUTOR.execute(() -> {
            clearDraft(appCtx);
            if (onComplete != null) {
                new Handler(Looper.getMainLooper()).post(onComplete);
            }
        });
    }

    /**
     * Builds an Intent to resume the active draft in UnifiedEditorActivity.
     */
    @NonNull
    public static Intent createResumeIntent(@NonNull Context context) {
        Intent intent = new Intent(context, UnifiedEditorActivity.class);
        intent.putExtra(EXTRA_RESUME_DRAFT, true);
        return intent;
    }

    private static void copyFileIfNotSame(String srcPath, File dest) throws IOException {
        if (srcPath == null) return;
        File src = new File(srcPath);
        if (!src.exists()) return;
        if (src.getAbsolutePath().equals(dest.getAbsolutePath())) return;

        copyFile(src, dest);
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }
}
