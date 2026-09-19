package com.anscanner.app.ui.crop;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * Dedicated activity for editing multi-page PDF documents.
 * Subclasses {@link CropPreviewActivity} and handles direct PDF intents,
 * extracting all pages into the session.
 */
public class EditPdfActivity extends CropPreviewActivity {

    public static Intent createIntent(Context context, Uri pdfUri, int initialPageIndex) {
        Intent intent = new Intent(context, EditPdfActivity.class);
        intent.putExtra(EXTRA_PDF_URI, pdfUri);
        intent.putExtra(EXTRA_PAGE_INDEX, initialPageIndex);
        intent.putExtra(EXTRA_MODE, MODE_PDF_EDIT);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
