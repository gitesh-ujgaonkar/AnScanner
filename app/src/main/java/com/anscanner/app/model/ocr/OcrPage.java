package com.anscanner.app.model.ocr;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Hierarchical, parcelable data model representing an entire page's OCR recognition output down to words.
 */
public class OcrPage implements Parcelable {
    private final int bitmapWidth;
    private final int bitmapHeight;
    private String fullText;
    private final List<OcrBlock> blocks;
    private final List<OcrLine> lines;
    private final List<OcrElement> elements;

    public OcrPage(int bitmapWidth, int bitmapHeight, @NonNull String fullText,
                   @NonNull List<OcrBlock> blocks, @NonNull List<OcrLine> lines,
                   @NonNull List<OcrElement> elements) {
        this.bitmapWidth = bitmapWidth;
        this.bitmapHeight = bitmapHeight;
        this.fullText = fullText != null ? fullText : "";
        this.blocks = blocks != null ? blocks : new ArrayList<>();
        this.lines = lines != null ? lines : new ArrayList<>();
        this.elements = elements != null ? elements : new ArrayList<>();
    }

    protected OcrPage(Parcel in) {
        bitmapWidth = in.readInt();
        bitmapHeight = in.readInt();
        fullText = in.readString();
        blocks = in.createTypedArrayList(OcrBlock.CREATOR);
        lines = in.createTypedArrayList(OcrLine.CREATOR);
        elements = in.createTypedArrayList(OcrElement.CREATOR);
    }

    public static final Creator<OcrPage> CREATOR = new Creator<OcrPage>() {
        @Override
        public OcrPage createFromParcel(Parcel in) {
            return new OcrPage(in);
        }

        @Override
        public OcrPage[] newArray(int size) {
            return new OcrPage[size];
        }
    };

    public int getBitmapWidth() { return bitmapWidth; }
    public int getBitmapHeight() { return bitmapHeight; }
    @NonNull public String getFullText() { return fullText; }
    public void setFullText(@NonNull String text) { this.fullText = text != null ? text : ""; }
    @NonNull public List<OcrBlock> getBlocks() { return blocks; }
    @NonNull public List<OcrLine> getLines() { return lines; }
    @NonNull public List<OcrElement> getElements() { return elements; }

    public boolean isEmpty() {
        return elements.isEmpty() && (fullText == null || fullText.trim().isEmpty());
    }

    @NonNull
    public String getSelectedText(int startIndex, int endIndex) {
        if (elements.isEmpty() || startIndex < 0 || endIndex < 0 || startIndex > endIndex) {
            return "";
        }
        int start = Math.max(0, startIndex);
        int end = Math.min(elements.size() - 1, endIndex);

        StringBuilder sb = new StringBuilder();
        int currentLine = -1;

        for (int i = start; i <= end; i++) {
            OcrElement elem = elements.get(i);
            if (currentLine != -1) {
                if (elem.getLineIndex() != currentLine) {
                    sb.append("\n");
                } else {
                    sb.append(" ");
                }
            }
            sb.append(elem.getText());
            currentLine = elem.getLineIndex();
        }
        return sb.toString().trim();
    }

    @NonNull
    public static OcrPage fromMlKitText(@Nullable Text visionText, int bitmapWidth, int bitmapHeight) {
        if (visionText == null || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return new OcrPage(bitmapWidth, bitmapHeight, "", new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        List<OcrBlock> ocrBlocks = new ArrayList<>();
        List<OcrLine> ocrLines = new ArrayList<>();
        List<OcrElement> ocrElements = new ArrayList<>();

        int globalLineIndex = 0;
        int globalElementIndex = 0;

        List<Text.TextBlock> textBlocks = visionText.getTextBlocks();
        for (int b = 0; b < textBlocks.size(); b++) {
            Text.TextBlock block = textBlocks.get(b);
            Rect bBox = block.getBoundingBox();
            RectF bNorm = normalizeRect(bBox, bitmapWidth, bitmapHeight);

            List<OcrLine> blockLines = new ArrayList<>();

            for (Text.Line line : block.getLines()) {
                Rect lBox = line.getBoundingBox();
                RectF lNorm = normalizeRect(lBox, bitmapWidth, bitmapHeight);

                List<OcrElement> lineElements = new ArrayList<>();
                int startElem = globalElementIndex;

                for (Text.Element element : line.getElements()) {
                    Rect eBox = element.getBoundingBox();
                    RectF eNorm = normalizeRect(eBox, bitmapWidth, bitmapHeight);
                    OcrElement ocrElem = new OcrElement(globalElementIndex, globalLineIndex, b,
                            element.getText(), eBox, eNorm);
                    lineElements.add(ocrElem);
                    ocrElements.add(ocrElem);
                    globalElementIndex++;
                }

                int endElem = Math.max(startElem, globalElementIndex - 1);
                OcrLine ocrLine = new OcrLine(globalLineIndex, b, line.getText(), lBox, lNorm,
                        startElem, endElem, lineElements);
                blockLines.add(ocrLine);
                ocrLines.add(ocrLine);
                globalLineIndex++;
            }

            OcrBlock ocrBlock = new OcrBlock(b, block.getText(), bBox, bNorm, blockLines);
            ocrBlocks.add(ocrBlock);
        }

        return new OcrPage(bitmapWidth, bitmapHeight, visionText.getText(), ocrBlocks, ocrLines, ocrElements);
    }

    private static RectF normalizeRect(@Nullable Rect box, int width, int height) {
        if (box == null || width <= 0 || height <= 0) {
            return new RectF();
        }
        return new RectF(
                (float) box.left / width,
                (float) box.top / height,
                (float) box.right / width,
                (float) box.bottom / height
        );
    }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(bitmapWidth);
        dest.writeInt(bitmapHeight);
        dest.writeString(fullText);
        dest.writeTypedList(blocks);
        dest.writeTypedList(lines);
        dest.writeTypedList(elements);
    }
}
