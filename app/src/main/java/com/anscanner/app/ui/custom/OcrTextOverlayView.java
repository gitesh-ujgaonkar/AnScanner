package com.anscanner.app.ui.custom;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.model.ocr.OcrPage;
import com.google.mlkit.vision.text.Text;

/**
 * Backward-compatible subclass extending {@link OcrSelectionOverlayView}.
 * Renders fluid, Google Lens-style selectable text copying down to individual words/lines.
 */
public class OcrTextOverlayView extends OcrSelectionOverlayView {

    public interface OnTextSelectedListener {
        void onBlockSelected(@Nullable Text.TextBlock block, @NonNull String text);
        void onSelectionCleared();
    }

    public OcrTextOverlayView(@NonNull Context context) {
        super(context);
    }

    public OcrTextOverlayView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public OcrTextOverlayView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnTextSelectedListener(@Nullable OnTextSelectedListener listener) {
        if (listener == null) {
            setOnSelectionChangeListener(null);
        } else {
            setOnSelectionChangeListener((selectedText, hasSelection) -> {
                if (hasSelection) {
                    listener.onBlockSelected(null, selectedText);
                } else {
                    listener.onSelectionCleared();
                }
            });
        }
    }

    public void setVisionText(@Nullable Text visionText, int bitmapWidth, int bitmapHeight) {
        if (visionText == null) {
            setOcrPage(null);
        } else {
            setOcrPage(OcrPage.fromMlKitText(visionText, bitmapWidth, bitmapHeight));
        }
    }

    public void clear() {
        setOcrPage(null);
        clearSelection();
    }
}
