package com.anscanner.app.ui.library;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityLibraryBinding;
import com.anscanner.app.service.CrashManager;
import com.anscanner.app.ui.camera.CameraActivity;
import com.anscanner.app.ui.onboarding.OnboardingActivity;
import com.anscanner.app.ui.pdf.MergePdfBottomSheet;
import com.anscanner.app.ui.settings.SettingsActivity;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;

public class LibraryActivity extends AppCompatActivity implements MergePdfBottomSheet.OnPdfMergedListener {
    private static final String TAG = "LibraryActivity";

    private ActivityLibraryBinding binding;
    private LibraryPagerAdapter pagerAdapter;

    private final ActivityResultLauncher<Intent> mergePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    ArrayList<Uri> uris = new ArrayList<>();
                    ArrayList<String> titles = new ArrayList<>();

                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            Uri uri = data.getClipData().getItemAt(i).getUri();
                            if (uri != null) {
                                try {
                                    final int takeFlags = data.getFlags()
                                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                                    getContentResolver().takePersistableUriPermission(
                                            uri, takeFlags != 0 ? takeFlags : Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                } catch (Exception ignored) {}
                                uris.add(uri);
                                titles.add(queryDisplayName(uri));
                            }
                        }
                    } else if (data.getData() != null) {
                        Uri uri = data.getData();
                        try {
                            final int takeFlags = data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            getContentResolver().takePersistableUriPermission(
                                    uri, takeFlags != 0 ? takeFlags : Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {}
                        uris.add(uri);
                        titles.add(queryDisplayName(uri));
                    }

                    if (uris.size() < 2) {
                        Toast.makeText(this, R.string.merge_min_files_error, Toast.LENGTH_SHORT).show();
                    } else {
                        MergePdfBottomSheet sheet = MergePdfBottomSheet.newInstance(uris, titles);
                        sheet.show(getSupportFragmentManager(), "MergePdfBottomSheet");
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.anscanner.app.util.InsetsHelper.applyEdgeToEdge(this);
        super.onCreate(savedInstanceState);

        // Check if this is the first app launch; show onboarding if so
        SharedPreferences prefs = getSharedPreferences(OnboardingActivity.PREF_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(OnboardingActivity.KEY_IS_FIRST_LAUNCH, true)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }

        binding = ActivityLibraryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() |
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout());

            binding.getRoot().setPadding(insets.left, 0, insets.right, 0);

            if (binding.topSection != null) {
                binding.topSection.setPadding(
                        binding.topSection.getPaddingLeft(),
                        insets.top,
                        binding.topSection.getPaddingRight(),
                        binding.topSection.getPaddingBottom());
            }

            if (binding.bottomNavContainer != null) {
                binding.bottomNavContainer.setPadding(
                        binding.bottomNavContainer.getPaddingLeft(),
                        binding.bottomNavContainer.getPaddingTop(),
                        binding.bottomNavContainer.getPaddingRight(),
                        insets.bottom);
            }

            if (binding.adView != null) {
                boolean isLandscape = getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                android.view.ViewGroup.MarginLayoutParams params =
                        (android.view.ViewGroup.MarginLayoutParams) binding.adView.getLayoutParams();
                params.bottomMargin = isLandscape ? insets.bottom : 0;
                binding.adView.setLayoutParams(params);
            }

            return windowInsets;
        });

        // Prompt user if a crash occurred previously
        CrashManager.checkAndPromptCrashLog(this);

        AdRequest adRequest = new AdRequest.Builder().build();
        binding.adView.loadAd(adRequest);

        setupViewPagerAndTabs();
        setupBottomNav();
        setupSearch();

        if (binding.btnMergePdfsHeader != null) {
            binding.btnMergePdfsHeader.setOnClickListener(v -> launchPdfPickerForMerge());
        }
    }

    @Override
    protected void onPause() {
        if (binding.adView != null) {
            binding.adView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding.adView != null) {
            binding.adView.resume();
        }
    }

    private void setupViewPagerAndTabs() {
        pagerAdapter = new LibraryPagerAdapter(this);
        binding.viewPager.setAdapter(pagerAdapter);

        new TabLayoutMediator(binding.tabLayout, binding.viewPager, (tab, position) -> {
            if (position == 0) {
                tab.setText(R.string.tab_scanned_in_app);
            } else {
                tab.setText(R.string.tab_all_device_pdfs);
            }
        }).attach();

        // Smooth subtle page cross-fade and scale transition for tab changes
        binding.viewPager.setPageTransformer((page, position) -> {
            float absPos = Math.abs(position);
            page.setAlpha(1.0f - 0.2f * absPos);
        });
    }

    private void setupBottomNav() {
        binding.fabCamera.setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)));

        binding.navSettings.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
        });

        binding.navLibrary.setOnClickListener(v -> {
            if (pagerAdapter != null) {
                pagerAdapter.scrollToTop(binding.viewPager.getCurrentItem());
            }
        });
    }

    private void setupSearch() {
        binding.searchView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (pagerAdapter != null) {
                    pagerAdapter.setFilterQuery(s != null ? s.toString() : "");
                }
            }
        });
    }

    private void launchPdfPickerForMerge() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        mergePickerLauncher.launch(intent);
    }

    private String queryDisplayName(Uri uri) {
        String result = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : "Document.pdf";
    }

    @Override
    public void onPdfMergedSuccess() {
        if (pagerAdapter != null) {
            pagerAdapter.reloadCurrentTab();
        }
    }

    @Override
    protected void onDestroy() {
        if (binding != null && binding.adView != null) {
            binding.adView.destroy();
        }
        super.onDestroy();
    }
}
