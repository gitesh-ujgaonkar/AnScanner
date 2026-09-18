package com.anscanner.app.ui.review;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityReviewScanBinding;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.ui.camera.CameraActivity;
import com.anscanner.app.ui.save.SaveScanBottomSheet;

import java.util.ArrayList;
import java.util.Collections;

public class ReviewScanActivity extends AppCompatActivity implements PageGridAdapter.PageInteractionListener {

    public static final String EXTRA_PAGE_PATHS = "extra_page_paths";
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_ORIGINAL_PAGE_PATHS = "extra_original_page_paths";

    private ActivityReviewScanBinding binding;
    private ArrayList<String> pagePaths;
    private ArrayList<String> originalPagePaths;
    private PageGridAdapter adapter;

    /**
     * Launches CameraActivity in "add page" mode.
     *
     * <p>Result chain:
     * ReviewScanActivity → CameraActivity → CropPreviewActivity
     * CropPreviewActivity sets RESULT_OK + EXTRA_CROPPED_PATH →
     * CameraActivity.cropForResultLauncher relays it →
     * this callback receives it.</p>
     */
    private final ActivityResultLauncher<Intent> addPageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    ArrayList<String> multiplePaths = result.getData().getStringArrayListExtra(EXTRA_PAGE_PATHS);
                    String croppedPath = result.getData().getStringExtra(
                            CameraActivity.EXTRA_CROPPED_PATH);

                    if (multiplePaths != null && !multiplePaths.isEmpty()) {
                        int startPos = pagePaths.size();
                        pagePaths.addAll(multiplePaths);
                        originalPagePaths.addAll(multiplePaths);
                        adapter.updatePages(pagePaths);
                        adapter.notifyItemRangeInserted(startPos, multiplePaths.size());
                        updatePageCount();
                    } else if (croppedPath != null) {
                        pagePaths.add(croppedPath);
                        originalPagePaths.add(croppedPath);
                        int insertedPosition = pagePaths.size() - 1;
                        adapter.updatePages(pagePaths);
                        adapter.notifyItemInserted(insertedPosition);
                        updatePageCount();
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> editPageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getData() != null) {
                    ArrayList<String> updated = result.getData().getStringArrayListExtra(EXTRA_PAGE_PATHS);
                    ArrayList<String> updatedOriginal = result.getData().getStringArrayListExtra(EXTRA_ORIGINAL_PAGE_PATHS);
                    if (updated != null) {
                        pagePaths.clear();
                        pagePaths.addAll(updated);
                        if (updatedOriginal != null) {
                            originalPagePaths.clear();
                            originalPagePaths.addAll(updatedOriginal);
                        }
                        adapter.updatePages(pagePaths);
                        updatePageCount();
                        if (pagePaths.isEmpty()) {
                            Intent intent = new Intent(this, CameraActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                        }
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityReviewScanBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        pagePaths = getIntent().getStringArrayListExtra(EXTRA_PAGE_PATHS);
        if (pagePaths == null || pagePaths.isEmpty()) {
            Uri pdfUri = getIntent().getData();
            if (pdfUri == null && getIntent().hasExtra("extra_pdf_uri")) {
                pdfUri = getIntent().getParcelableExtra("extra_pdf_uri");
            }
            String imagePath = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
            if (pdfUri == null && imagePath != null && imagePath.toLowerCase().endsWith(".pdf")) {
                pdfUri = Uri.fromFile(new File(imagePath));
            }
            if (pdfUri != null) {
                pagePaths = com.anscanner.app.ui.crop.CropPreviewActivity.extractAllPagesFromPdf(this, pdfUri);
            }
        }
        if (pagePaths == null) {
            pagePaths = new ArrayList<>();
        }
        String singleImage = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        if (pagePaths.isEmpty() && singleImage != null && !singleImage.toLowerCase().endsWith(".pdf")) {
            pagePaths.add(singleImage);
        }

        originalPagePaths = getIntent().getStringArrayListExtra(EXTRA_ORIGINAL_PAGE_PATHS);
        if (originalPagePaths == null || originalPagePaths.size() != pagePaths.size()) {
            originalPagePaths = new ArrayList<>(pagePaths);
        }

        setupRecyclerView();
        setupClickListeners();
        updatePageCount();
    }

    private void setupRecyclerView() {
        adapter = new PageGridAdapter(pagePaths, this);
        binding.rvPages.setLayoutManager(new GridLayoutManager(this, 2));
        binding.rvPages.setAdapter(adapter);

        ItemTouchHelper.SimpleCallback simpleCallback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.START | ItemTouchHelper.END, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                int fromPosition = viewHolder.getBindingAdapterPosition();
                int toPosition = target.getBindingAdapterPosition();

                if (fromPosition < 0 || toPosition < 0
                        || fromPosition >= pagePaths.size() || toPosition >= pagePaths.size()) {
                    return false;
                }

                Collections.swap(pagePaths, fromPosition, toPosition);
                if (fromPosition < originalPagePaths.size() && toPosition < originalPagePaths.size()) {
                    Collections.swap(originalPagePaths, fromPosition, toPosition);
                }
                adapter.notifyItemMoved(fromPosition, toPosition);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // Swiping disabled
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                adapter.notifyDataSetChanged();
            }
        };

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleCallback);
        itemTouchHelper.attachToRecyclerView(binding.rvPages);
    }

    private void setupClickListeners() {
        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnRetake.setOnClickListener(v -> {
            CacheManager.clearScanCache(this);
            Intent intent = new Intent(this, CameraActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });

        binding.btnProceed.setOnClickListener(v -> {
            if (pagePaths.isEmpty()) {
                Toast.makeText(this, "No pages to save", Toast.LENGTH_SHORT).show();
                return;
            }
            // Pass the fully-updated pagePaths list (including any added pages)
            SaveScanBottomSheet bottomSheet = SaveScanBottomSheet.newInstance(pagePaths);
            bottomSheet.show(getSupportFragmentManager(), "SaveScanBottomSheet");
        });
    }

    private void updatePageCount() {
        binding.tvPageCount.setText(getString(R.string.review_page_number, pagePaths.size()));
    }

    @Override
    public void onPageDeleteClick(int position) {
        if (position >= 0 && position < pagePaths.size()) {
            pagePaths.remove(position);
            if (position < originalPagePaths.size()) {
                originalPagePaths.remove(position);
            }
            adapter.updatePages(pagePaths);
            updatePageCount();

            if (pagePaths.isEmpty()) {
                Intent intent = new Intent(this, CameraActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                finish();
            }
        }
    }

    /**
     * "Add Page" tile was tapped.
     * Launches CameraActivity with EXTRA_IS_ADDING_PAGE = true via the
     * ActivityResultLauncher so that the new cropped page path comes back
     * as EXTRA_CROPPED_PATH in the result Intent.
     */
    @Override
    public void onAddPageClick() {
        Intent intent = new Intent(this, CameraActivity.class);
        intent.putExtra(CameraActivity.EXTRA_IS_ADDING_PAGE, true);
        addPageLauncher.launch(intent);
    }

    @Override
    public void onPageClick(int position) {
        if (position >= 0 && position < pagePaths.size()) {
            Intent intent = new Intent(this, com.anscanner.app.ui.crop.CropPreviewActivity.class);
            intent.putExtra(com.anscanner.app.ui.crop.CropPreviewActivity.EXTRA_IMAGE_PATH, pagePaths.get(position));
            intent.putStringArrayListExtra(com.anscanner.app.ui.crop.CropPreviewActivity.EXTRA_PAGE_PATHS, pagePaths);
            intent.putStringArrayListExtra(com.anscanner.app.ui.crop.CropPreviewActivity.EXTRA_ORIGINAL_PAGE_PATHS, originalPagePaths);
            intent.putExtra(com.anscanner.app.ui.crop.CropPreviewActivity.EXTRA_PAGE_INDEX, position);
            intent.putExtra(com.anscanner.app.ui.crop.CropPreviewActivity.EXTRA_FROM_REVIEW, true);
            editPageLauncher.launch(intent);
        }
    }
}
