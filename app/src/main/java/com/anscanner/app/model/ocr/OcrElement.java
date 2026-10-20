package com.anscanner.app.model.ocr;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Represents an individual word token (ML Kit Text.Element) with pixel and normalized coordinates.
 */
public class OcrElement implements Parcelable {
    private final int index;
    private final int lineIndex;
    private final int blockIndex;
    private final String text;
    private final Rect boundingBox;
    private final RectF normalizedRect;

    public OcrElement(int index, int lineIndex, int blockIndex, @NonNull String text,
                      @Nullable Rect boundingBox, @NonNull RectF normalizedRect) {
        this.index = index;
        this.lineIndex = lineIndex;
        this.blockIndex = blockIndex;
        this.text = text != null ? text : "";
        this.boundingBox = boundingBox != null ? boundingBox : new Rect();
        this.normalizedRect = normalizedRect != null ? normalizedRect : new RectF();
    }

    protected OcrElement(Parcel in) {
        index = in.readInt();
        lineIndex = in.readInt();
        blockIndex = in.readInt();
        text = in.readString();
        boundingBox = in.readParcelable(Rect.class.getClassLoader());
        normalizedRect = in.readParcelable(RectF.class.getClassLoader());
    }

    public static final Creator<OcrElement> CREATOR = new Creator<OcrElement>() {
        @Override
        public OcrElement createFromParcel(Parcel in) {
            return new OcrElement(in);
        }

        @Override
        public OcrElement[] newArray(int size) {
            return new OcrElement[size];
        }
    };

    public int getIndex() { return index; }
    public int getLineIndex() { return lineIndex; }
    public int getBlockIndex() { return blockIndex; }
    @NonNull public String getText() { return text; }
    @NonNull public Rect getBoundingBox() { return boundingBox; }
    @NonNull public RectF getNormalizedRect() { return normalizedRect; }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(index);
        dest.writeInt(lineIndex);
        dest.writeInt(blockIndex);
        dest.writeString(text);
        dest.writeParcelable(boundingBox, flags);
        dest.writeParcelable(normalizedRect, flags);
    }
}
