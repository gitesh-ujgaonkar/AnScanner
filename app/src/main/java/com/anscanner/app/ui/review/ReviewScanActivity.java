package com.anscanner.app.ui.review;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

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

    private ActivityReviewScanBinding binding;
    private ArrayList<String> pagePaths;
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
                        adapter.updatePages(pagePaths);
                        adapter.notifyItemRangeInserted(startPos, multiplePaths.size());
                        updatePageCount();
                    } else if (croppedPath != null) {
                        pagePaths.add(croppedPath);
                        int insertedPosition = pagePaths.size() - 1;
                        adapter.updatePages(pagePaths);
                        adapter.notifyItemInserted(insertedPosition);
                        updatePageCount();
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
        if (pagePaths == null) {
            pagePaths = new ArrayList<>();
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
        // Could open full-page preview or re-edit in CropPreviewActivity
    }
}
