package com.anscanner.app.ui.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.R;
import com.anscanner.app.model.ocr.OcrPage;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

/**
 * Material BottomSheet modal displaying full page extracted text inside a scrollable view.
 * Supports Copy All, Share, and live Text Editing.
 */
public class OcrTextBottomSheetDialog {

    public interface OnTextEditedListener {
        void onTextEdited(@NonNull String newText);
    }

    public static void show(@NonNull Context context, @Nullable OcrPage page, @Nullable OnTextEditedListener listener) {
        String fullText = page != null ? page.getFullText() : "";
        show(context, fullText, listener);
    }

    public static void show(@NonNull Context context, @Nullable String text, @Nullable OnTextEditedListener listener) {
        final BottomSheetDialog dialog = new BottomSheetDialog(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ocr_text_bottom_sheet, null);

        TextView tvContent = view.findViewById(R.id.tvBottomSheetContent);
        EditText etContent = view.findViewById(R.id.etBottomSheetContent);
        MaterialButton btnCopy = view.findViewById(R.id.btnBottomSheetCopy);
        MaterialButton btnShare = view.findViewById(R.id.btnBottomSheetShare);
        MaterialButton btnEdit = view.findViewById(R.id.btnBottomSheetEdit);

        final String initialText = (text == null || text.trim().isEmpty())
                ? context.getString(R.string.ocr_empty)
                : text.trim();

        tvContent.setText(initialText);

        btnCopy.setOnClickListener(v -> {
            String currentText = etContent.getVisibility() == View.VISIBLE
                    ? etContent.getText().toString()
                    : tvContent.getText().toString();
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && !currentText.isEmpty()) {
                cm.setPrimaryClip(ClipData.newPlainText("Extracted Text", currentText));
                Toast.makeText(context, R.string.ocr_copied, Toast.LENGTH_SHORT).show();
            }
        });

        btnShare.setOnClickListener(v -> {
            String currentText = etContent.getVisibility() == View.VISIBLE
                    ? etContent.getText().toString()
                    : tvContent.getText().toString();
            if (!currentText.isEmpty()) {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Extracted Text");
                shareIntent.putExtra(Intent.EXTRA_TEXT, currentText);
                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.ocr_dialog_share)));
            }
        });

        btnEdit.setOnClickListener(v -> {
            if (etContent.getVisibility() == View.GONE) {
                // Switch to Edit Mode
                etContent.setText(tvContent.getText());
                etContent.setVisibility(View.VISIBLE);
                tvContent.setVisibility(View.GONE);
                btnEdit.setText(R.string.ocr_save);
                btnEdit.setIconResource(R.drawable.ic_check);
                etContent.requestFocus();
            } else {
                // Save edited text
                String updatedText = etContent.getText().toString().trim();
                tvContent.setText(updatedText);
                etContent.setVisibility(View.GONE);
                tvContent.setVisibility(View.VISIBLE);
                btnEdit.setText(R.string.ocr_edit);
                btnEdit.setIconResource(R.drawable.ic_edit);

                if (listener != null) {
                    listener.onTextEdited(updatedText);
                }
            }
        });

        dialog.setContentView(view);
        dialog.show();
    }
}
