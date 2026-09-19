package com.anscanner.app.ui.save;

import android.net.Uri;
import android.os.Bundle;

/**
 * Alias for {@link CompressPdfBottomSheet} providing full API compatibility
 * under the {@code CompressBottomSheetDialog} naming convention.
 */
public class CompressBottomSheetDialog extends CompressPdfBottomSheet {

    private static final String ARG_PDF_URI = "arg_pdf_uri";

    public static CompressBottomSheetDialog newInstance(Uri pdfUri) {
        CompressBottomSheetDialog fragment = new CompressBottomSheetDialog();
        Bundle args = new Bundle();
        args.putParcelable(ARG_PDF_URI, pdfUri);
        fragment.setArguments(args);
        return fragment;
    }
}
