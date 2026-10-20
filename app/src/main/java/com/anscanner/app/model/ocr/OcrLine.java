package com.anscanner.app.model.ocr;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single recognized line (ML Kit Text.Line) with bounding box and element range.
 */
public class OcrLine implements Parcelable {
    private final int lineIndex;
    private final int blockIndex;
    private final String text;
    private final Rect boundingBox;
    private final RectF normalizedRect;
    private final int startElementIndex;
    private final int endElementIndex;
    private final List<OcrElement> elements;

    public OcrLine(int lineIndex, int blockIndex, @NonNull String text,
                   @Nullable Rect boundingBox, @NonNull RectF normalizedRect,
                   int startElementIndex, int endElementIndex, @NonNull List<OcrElement> elements) {
        this.lineIndex = lineIndex;
        this.blockIndex = blockIndex;
        this.text = text != null ? text : "";
        this.boundingBox = boundingBox != null ? boundingBox : new Rect();
        this.normalizedRect = normalizedRect != null ? normalizedRect : new RectF();
        this.startElementIndex = startElementIndex;
        this.endElementIndex = endElementIndex;
        this.elements = elements != null ? elements : new ArrayList<>();
    }

    protected OcrLine(Parcel in) {
        lineIndex = in.readInt();
        blockIndex = in.readInt();
        text = in.readString();
        boundingBox = in.readParcelable(Rect.class.getClassLoader());
        normalizedRect = in.readParcelable(RectF.class.getClassLoader());
        startElementIndex = in.readInt();
        endElementIndex = in.readInt();
        elements = in.createTypedArrayList(OcrElement.CREATOR);
    }

    public static final Creator<OcrLine> CREATOR = new Creator<OcrLine>() {
        @Override
        public OcrLine createFromParcel(Parcel in) {
            return new OcrLine(in);
        }

        @Override
        public OcrLine[] newArray(int size) {
            return new OcrLine[size];
        }
    };

    public int getLineIndex() { return lineIndex; }
    public int getBlockIndex() { return blockIndex; }
    @NonNull public String getText() { return text; }
    @NonNull public Rect getBoundingBox() { return boundingBox; }
    @NonNull public RectF getNormalizedRect() { return normalizedRect; }
    public int getStartElementIndex() { return startElementIndex; }
    public int getEndElementIndex() { return endElementIndex; }
    @NonNull public List<OcrElement> getElements() { return elements; }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(lineIndex);
        dest.writeInt(blockIndex);
        dest.writeString(text);
        dest.writeParcelable(boundingBox, flags);
        dest.writeParcelable(normalizedRect, flags);
        dest.writeInt(startElementIndex);
        dest.writeInt(endElementIndex);
        dest.writeTypedList(elements);
    }
}
