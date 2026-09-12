package com.anscanner.app.ui.review;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityReviewScanBinding;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.ui.save.SaveScanBottomSheet;
import java.util.ArrayList;

public class ReviewScanActivity extends AppCompatActivity implements PageGridAdapter.PageInteractionListener {

    public static final String EXTRA_PAGE_PATHS = "extra_page_paths";
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    
    private ActivityReviewScanBinding binding;
    private ArrayList<String> pagePaths;
    private PageGridAdapter adapter;
    
    private final ActivityResultLauncher<Intent> addPageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String newPath = result.getData().getStringExtra(EXTRA_IMAGE_PATH);
                    if (newPath != null) {
                        pagePaths.add(newPath);
                        updateUi();
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
        updateUi();
    }

    private void setupRecyclerView() {
        adapter = new PageGridAdapter(pagePaths, this);
        binding.rvPages.setLayoutManager(new GridLayoutManager(this, 2));
        binding.rvPages.setAdapter(adapter);
    }
    
    private void setupClickListeners() {
        binding.btnBack.setOnClickListener(v -> finish());
        
        binding.btnRetake.setOnClickListener(v -> {
            CacheManager.clearScanCache(this);
            Intent intent = new Intent();
            intent.setClassName(this, "com.anscanner.app.ui.camera.CameraActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });
        
        binding.btnProceed.setOnClickListener(v -> {
            if (pagePaths.isEmpty()) {
                Toast.makeText(this, "No pages to save", Toast.LENGTH_SHORT).show();
                return;
            }
            SaveScanBottomSheet bottomSheet = SaveScanBottomSheet.newInstance(pagePaths);
            bottomSheet.show(getSupportFragmentManager(), "SaveScanBottomSheet");
        });
    }
    
    private void updateUi() {
        adapter.updatePages(pagePaths);
        binding.tvPageCount.setText(getString(R.string.review_page_number, pagePaths.size()));
    }

    @Override
    public void onPageDeleteClick(int position) {
        if (position >= 0 && position < pagePaths.size()) {
            pagePaths.remove(position);
            updateUi();
            if (pagePaths.isEmpty()) {
                Intent intent = new Intent();
                intent.setClassName(this, "com.anscanner.app.ui.camera.CameraActivity");
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                finish();
            }
        }
    }

    @Override
    public void onAddPageClick() {
        Intent intent = new Intent();
        intent.setClassName(this, "com.anscanner.app.ui.camera.CameraActivity");
        intent.putExtra("is_add_page", true);
        addPageLauncher.launch(intent);
    }

    @Override
    public void onPageClick(int position) {
        // Handled if full page preview needed
    }
}
