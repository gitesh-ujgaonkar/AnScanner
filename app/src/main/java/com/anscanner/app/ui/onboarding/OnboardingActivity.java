package com.anscanner.app.ui.onboarding;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityOnboardingBinding;
import com.anscanner.app.databinding.ItemOnboardingPageBinding;
import com.anscanner.app.ui.camera.CameraActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * First-launch onboarding walkthrough that introduces AnScanner's key features:
 * 1. Document scanning with real-time edge detection
 * 2. Drag-to-reorder multi-page editing
 * 3. Offline ML Kit OCR text extraction
 */
public class OnboardingActivity extends AppCompatActivity {

    public static final String PREF_NAME = "anscanner_prefs";
    public static final String KEY_IS_FIRST_LAUNCH = "is_first_launch";

    private ActivityOnboardingBinding binding;
    private final List<OnboardingSlide> slides = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityOnboardingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        initSlides();
        setupViewPager();
        setupActions();
    }

    private void initSlides() {
        slides.add(new OnboardingSlide(
                R.drawable.ic_camera,
                R.string.onboarding_slide1_title,
                R.string.onboarding_slide1_desc
        ));
        slides.add(new OnboardingSlide(
                R.drawable.ic_drag_handle,
                R.string.onboarding_slide2_title,
                R.string.onboarding_slide2_desc
        ));
        slides.add(new OnboardingSlide(
                R.drawable.ic_ocr_text,
                R.string.onboarding_slide3_title,
                R.string.onboarding_slide3_desc
        ));
    }

    private void setupViewPager() {
        OnboardingAdapter adapter = new OnboardingAdapter(slides);
        binding.viewPager.setAdapter(adapter);

        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                updateUiForPage(position);
            }
        });

        updateUiForPage(0);
    }

    private void setupActions() {
        binding.btnSkip.setOnClickListener(v -> finishOnboarding());

        binding.btnNext.setOnClickListener(v -> {
            int currentPosition = binding.viewPager.getCurrentItem();
            if (currentPosition < slides.size() - 1) {
                binding.viewPager.setCurrentItem(currentPosition + 1, true);
            } else {
                finishOnboarding();
            }
        });
    }

    private void updateUiForPage(int position) {
        // Update dot indicators
        binding.indicator0.setBackgroundResource(position == 0
                ? R.drawable.bg_indicator_active : R.drawable.bg_indicator_inactive);
        binding.indicator1.setBackgroundResource(position == 1
                ? R.drawable.bg_indicator_active : R.drawable.bg_indicator_inactive);
        binding.indicator2.setBackgroundResource(position == 2
                ? R.drawable.bg_indicator_active : R.drawable.bg_indicator_inactive);

        // Update button text and skip visibility
        if (position == slides.size() - 1) {
            binding.btnNext.setText(R.string.onboarding_get_started);
            binding.btnSkip.setVisibility(View.INVISIBLE);
        } else {
            binding.btnNext.setText(R.string.onboarding_next);
            binding.btnSkip.setVisibility(View.VISIBLE);
        }
    }

    private void finishOnboarding() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_IS_FIRST_LAUNCH, false).apply();

        Intent intent = new Intent(this, CameraActivity.class);
        startActivity(intent);
        finish();
    }

    // ── Data Class ──────────────────────────────────────────────────────────

    private static class OnboardingSlide {
        final int iconResId;
        final int titleResId;
        final int descResId;

        OnboardingSlide(int iconResId, int titleResId, int descResId) {
            this.iconResId = iconResId;
            this.titleResId = titleResId;
            this.descResId = descResId;
        }
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    private static class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.SlideViewHolder> {
        private final List<OnboardingSlide> slides;

        OnboardingAdapter(List<OnboardingSlide> slides) {
            this.slides = slides;
        }

        @NonNull
        @Override
        public SlideViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemOnboardingPageBinding binding = ItemOnboardingPageBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new SlideViewHolder(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull SlideViewHolder holder, int position) {
            holder.bind(slides.get(position));
        }

        @Override
        public int getItemCount() {
            return slides.size();
        }

        static class SlideViewHolder extends RecyclerView.ViewHolder {
            private final ItemOnboardingPageBinding itemBinding;

            SlideViewHolder(@NonNull ItemOnboardingPageBinding itemBinding) {
                super(itemBinding.getRoot());
                this.itemBinding = itemBinding;
            }

            void bind(OnboardingSlide slide) {
                itemBinding.ivOnboardingIcon.setImageResource(slide.iconResId);
                itemBinding.tvOnboardingTitle.setText(slide.titleResId);
                itemBinding.tvOnboardingDesc.setText(slide.descResId);
            }
        }
    }
}
