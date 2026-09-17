package com.anscanner.app.service;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;

import com.anscanner.app.R;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.DialogFilePropertiesBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Utility helper to extract java.io.File / MediaStore metadata and present
 * a formatted MaterialAlertDialogBuilder properties dialog.
 */
public final class FilePropertiesHelper {

    private FilePropertiesHelper() {
        // Static utility
    }

    public static void showPropertiesDialog(Context context, DocumentEntity document) {
        if (context == null || document == null) return;

        String fileName = document.title != null ? document.title : "Document";
        String filePath = document.getFilePath();
        long fileSizeBytes = document.fileSizeBytes;
        long lastModified = document.updatedAt > 0 ? document.updatedAt : document.createdAt;

        File physicalFile = null;
        if (filePath != null && !filePath.isEmpty() && !filePath.startsWith("content://")) {
            physicalFile = new File(filePath);
        }

        if (physicalFile != null && physicalFile.exists()) {
            fileName = physicalFile.getName();
            filePath = physicalFile.getAbsolutePath();
            fileSizeBytes = physicalFile.length();
            lastModified = physicalFile.lastModified();
        } else if (document.fileUri != null && document.fileUri.startsWith("content://")) {
            Uri contentUri = Uri.parse(document.fileUri);
            try (Cursor cursor = context.getContentResolver().query(contentUri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIdx != -1 && !cursor.isNull(nameIdx)) {
                        fileName = cursor.getString(nameIdx);
                    }
                    int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                        fileSizeBytes = cursor.getLong(sizeIdx);
                    }
                    int dataIdx = cursor.getColumnIndex("_data");
                    if (dataIdx != -1 && !cursor.isNull(dataIdx)) {
                        String data = cursor.getString(dataIdx);
                        if (data != null && !data.isEmpty()) {
                            filePath = data;
                        }
                    }
                    int dateIdx = cursor.getColumnIndex("date_modified");
                    if (dateIdx != -1 && !cursor.isNull(dateIdx)) {
                        lastModified = cursor.getLong(dateIdx) * 1000L;
                    }
                }
            } catch (Exception ignored) {}
        }

        // Format filename extension if missing
        if (!fileName.contains(".") && document.format != null) {
            fileName = fileName + ("PDF".equalsIgnoreCase(document.format) ? ".pdf" : ".jpg");
        }

        if (filePath == null || filePath.isEmpty()) {
            filePath = document.fileUri != null ? document.fileUri : "Internal Storage";
        }

        String formattedSize = StorageHelper.formatFileSize(fileSizeBytes);
        if (fileSizeBytes > 1024) {
            formattedSize += String.format(Locale.getDefault(), " (%,d bytes)", fileSizeBytes);
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
        String formattedDate = dateFormat.format(new Date(lastModified > 0 ? lastModified : System.currentTimeMillis()));

        DialogFilePropertiesBinding binding = DialogFilePropertiesBinding.inflate(LayoutInflater.from(context));
        binding.tvFileName.setText(fileName);
        binding.tvFilePath.setText(filePath);
        binding.tvFileSize.setText(formattedSize);
        binding.tvDateModified.setText(formattedDate);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.properties_title)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }
}
