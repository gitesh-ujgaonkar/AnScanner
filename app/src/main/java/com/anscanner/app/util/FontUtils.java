package com.anscanner.app.util;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized utility for resolving, loading, and caching typefaces for annotations
 * and document rendering.
 *
 * <p>Supports standard Android font families ("sans", "serif", "monospace", "cursive"),
 * resource fonts (via {@link ResourcesCompat#getFont}), asset fonts, and external font files
 * with graceful, visually distinct fallbacks.</p>
 */
public final class FontUtils {

    private static final String TAG = "FontUtils";
    private static final Map<String, Typeface> TYPEFACE_CACHE = new ConcurrentHashMap<>();

    private FontUtils() {
        // Utility class
    }

    /**
     * Resolves and returns a {@link Typeface} corresponding to the provided font key.
     * Results are cached for high-performance canvas rendering.
     *
     * @param context optional Android context for accessing resources or assets
     * @param fontKey font identifier (e.g. "sans", "serif", "mono", "cursive", asset path, or font resource)
     * @return resolved Typeface, guaranteed non-null
     */
    @NonNull
    public static Typeface getTypeface(@Nullable Context context, @Nullable String fontKey) {
        if (fontKey == null || fontKey.trim().isEmpty()) {
            return Typeface.SANS_SERIF;
        }

        Context ctx = context != null ? context : com.anscanner.app.AnScannerApp.getInstance();
        String key = fontKey.trim();
        String cacheKey = key.toLowerCase(Locale.US);

        Typeface cached = TYPEFACE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Typeface resolved = resolveTypeface(ctx, key, cacheKey);
        if (resolved == null) {
            resolved = Typeface.SANS_SERIF;
        }

        TYPEFACE_CACHE.put(cacheKey, resolved);
        return resolved;
    }

    /**
     * Convenience method to resolve a {@link Typeface} without requiring a Context.
     */
    @NonNull
    public static Typeface getTypeface(@Nullable String fontKey) {
        return getTypeface(null, fontKey);
    }

    @Nullable
    private static Typeface resolveTypeface(@Nullable Context context, @NonNull String rawKey, @NonNull String lowerKey) {
        Context ctx = context != null ? context : com.anscanner.app.AnScannerApp.getInstance();

        // 1. Standard Font Family Aliases
        switch (lowerKey) {
            case "sans":
            case "sans-serif":
            case "sans_serif":
            case "roboto":
            case "default":
                return Typeface.SANS_SERIF;

            case "serif":
            case "times":
            case "times new roman":
            case "playfair":
                if (ctx != null) {
                    try {
                        Typeface tf = Typeface.createFromAsset(ctx.getAssets(), "fonts/playfair.ttf");
                        if (tf != null) return tf;
                    } catch (Exception ignored) {}
                }
                return Typeface.SERIF;

            case "mono":
            case "monospace":
            case "courier":
            case "courier new":
            case "spacemono":
                if (ctx != null) {
                    try {
                        Typeface tf = Typeface.createFromAsset(ctx.getAssets(), "fonts/spacemono.ttf");
                        if (tf != null) return tf;
                    } catch (Exception ignored) {}
                }
                return Typeface.MONOSPACE;

            case "cursive":
            case "script":
            case "handwriting":
            case "caveat":
                if (ctx != null) {
                    try {
                        Typeface tf = Typeface.createFromAsset(ctx.getAssets(), "fonts/caveat.ttf");
                        if (tf != null) return tf;
                    } catch (Exception ignored) {}
                }
                return Typeface.create(Typeface.SERIF, Typeface.ITALIC);

            case "bold_condensed":
            case "condensed":
            case "impact":
                return Typeface.create("sans-serif-condensed", Typeface.BOLD);

            default:
                break;
        }

        // 2. Resource & Asset Font Loading (requires Context)
        if (context != null) {
            // A. Numeric resource ID
            try {
                int resId = Integer.parseInt(rawKey);
                Typeface tf = ResourcesCompat.getFont(context, resId);
                if (tf != null) {
                    return tf;
                }
            } catch (NumberFormatException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "Failed loading font by resource id: " + rawKey, e);
            }

            // B. Named font resource in res/font/
            try {
                String resName = lowerKey;
                if (resName.startsWith("@font/")) {
                    resName = resName.substring(7);
                } else if (resName.startsWith("r.font.")) {
                    resName = resName.substring(7);
                }
                int resId = context.getResources().getIdentifier(resName, "font", context.getPackageName());
                if (resId != 0) {
                    Typeface tf = ResourcesCompat.getFont(context, resId);
                    if (tf != null) {
                        return tf;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed loading font by resource name: " + rawKey, e);
            }

            // C. Asset font (e.g. assets/fonts/custom.ttf or fonts/custom.ttf)
            try {
                String assetPath = rawKey;
                if (assetPath.startsWith("assets/")) {
                    assetPath = assetPath.substring(7);
                }
                if (assetPath.endsWith(".ttf") || assetPath.endsWith(".otf") || assetPath.contains("/")) {
                    Typeface tf = Typeface.createFromAsset(context.getAssets(), assetPath);
                    if (tf != null) {
                        return tf;
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                Typeface tf = Typeface.createFromAsset(context.getAssets(), "fonts/" + rawKey);
                if (tf != null) {
                    return tf;
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Direct File on disk (if path points to a valid file)
        try {
            File file = new File(rawKey);
            if (file.exists() && file.isFile() && file.canRead()) {
                Typeface tf = Typeface.createFromFile(file);
                if (tf != null) {
                    return tf;
                }
            }
        } catch (Exception ignored) {
        }

        // 4. System Font Family Name Lookup
        try {
            Typeface custom = Typeface.create(rawKey, Typeface.NORMAL);
            if (custom != null && !custom.equals(Typeface.DEFAULT)) {
                return custom;
            }
        } catch (Exception ignored) {
        }

        // 5. Default Safe Fallback
        return Typeface.SANS_SERIF;
    }

    /**
     * Clears cached typefaces.
     */
    public static void clearCache() {
        TYPEFACE_CACHE.clear();
    }
}
