package com.anscanner.app.ui.pdf;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * PrintDocumentAdapter that feeds an existing PDF file directly to Android's
 * print spooler via stream copying without re-rendering.
 */
public class PdfPrintDocumentAdapter extends PrintDocumentAdapter {

    private static final String TAG = "PdfPrintAdapter";

    private final Context context;
    private final Uri documentUri;
    private final File documentFile;
    private final String documentName;
    private final int pageCount;

    public PdfPrintDocumentAdapter(Context context, Uri documentUri, String documentName, int pageCount) {
        this.context = context.getApplicationContext();
        this.documentUri = documentUri;
        this.documentFile = null;
        this.documentName = documentName;
        this.pageCount = pageCount;
    }

    public PdfPrintDocumentAdapter(Context context, File documentFile, String documentName, int pageCount) {
        this.context = context.getApplicationContext();
        this.documentUri = null;
        this.documentFile = documentFile;
        this.documentName = documentName;
        this.pageCount = pageCount;
    }

    @Override
    public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                         CancellationSignal cancellationSignal,
                         LayoutResultCallback callback, Bundle extras) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }

        PrintDocumentInfo info = new PrintDocumentInfo.Builder(documentName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pageCount > 0 ? pageCount : PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build();

        callback.onLayoutFinished(info, true);
    }

    @Override
    public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                        CancellationSignal cancellationSignal,
                        WriteResultCallback callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            callback.onWriteCancelled();
            return;
        }

        InputStream input = null;
        OutputStream output = null;

        try {
            if (documentUri != null) {
                input = context.getContentResolver().openInputStream(documentUri);
            } else if (documentFile != null) {
                input = new FileInputStream(documentFile);
            }

            if (input == null) {
                callback.onWriteFailed("Cannot open input PDF stream");
                return;
            }

            output = new FileOutputStream(destination.getFileDescriptor());

            byte[] buf = new byte[8192];
            int bytesRead;

            while ((bytesRead = input.read(buf)) > 0) {
                if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                    callback.onWriteCancelled();
                    return;
                }
                output.write(buf, 0, bytesRead);
            }
            output.flush();

            callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});

        } catch (IOException e) {
            Log.e(TAG, "Failed to write PDF to print spooler", e);
            callback.onWriteFailed(e.getMessage());
        } finally {
            try {
                if (input != null) input.close();
            } catch (IOException ignored) {}
            try {
                if (output != null) output.close();
            } catch (IOException ignored) {}
        }
    }
}
