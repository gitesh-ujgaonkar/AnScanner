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
 * Represents a recognized paragraph/block (ML Kit Text.TextBlock) with lines and normalized bounds.
 */
public class OcrBlock implements Parcelable {
    private final int blockIndex;
    private final String text;
    private final Rect boundingBox;
    private final RectF normalizedRect;
    private final List<OcrLine> lines;

    public OcrBlock(int blockIndex, @NonNull String text,
                    @Nullable Rect boundingBox, @NonNull RectF normalizedRect,
                    @NonNull List<OcrLine> lines) {
        this.blockIndex = blockIndex;
        this.text = text != null ? text : "";
        this.boundingBox = boundingBox != null ? boundingBox : new Rect();
        this.normalizedRect = normalizedRect != null ? normalizedRect : new RectF();
        this.lines = lines != null ? lines : new ArrayList<>();
    }

    protected OcrBlock(Parcel in) {
        blockIndex = in.readInt();
        text = in.readString();
        boundingBox = in.readParcelable(Rect.class.getClassLoader());
        normalizedRect = in.readParcelable(RectF.class.getClassLoader());
        lines = in.createTypedArrayList(OcrLine.CREATOR);
    }

    public static final Creator<OcrBlock> CREATOR = new Creator<OcrBlock>() {
        @Override
        public OcrBlock createFromParcel(Parcel in) {
            return new OcrBlock(in);
        }

        @Override
        public OcrBlock[] newArray(int size) {
            return new OcrBlock[size];
        }
    };

    public int getBlockIndex() { return blockIndex; }
    @NonNull public String getText() { return text; }
    @NonNull public Rect getBoundingBox() { return boundingBox; }
    @NonNull public RectF getNormalizedRect() { return normalizedRect; }
    @NonNull public List<OcrLine> getLines() { return lines; }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(blockIndex);
        dest.writeString(text);
        dest.writeParcelable(boundingBox, flags);
        dest.writeParcelable(normalizedRect, flags);
        dest.writeTypedList(lines);
    }
}
