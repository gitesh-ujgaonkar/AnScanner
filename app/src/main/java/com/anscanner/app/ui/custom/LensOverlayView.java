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
public class LensOverlayView extends OcrSelectionOverlayView {

    public interface OnDismissListener {
        void onDismiss();
    }

    public LensOverlayView(@NonNull Context context) {
        super(context);
    }

    public LensOverlayView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public LensOverlayView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnDismissListener(@Nullable OnDismissListener listener) {
        // Delegate if required
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
