package com.anscanner.app.ui.library;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * ViewPager2 adapter containing ScannedDocsFragment (Tab 0) and DeviceDocsFragment (Tab 1).
 */
public class LibraryPagerAdapter extends FragmentStateAdapter {

    private ScannedDocsFragment scannedDocsFragment;
    private DeviceDocsFragment deviceDocsFragment;

    public LibraryPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) {
            scannedDocsFragment = new ScannedDocsFragment();
            return scannedDocsFragment;
        } else {
            deviceDocsFragment = new DeviceDocsFragment();
            return deviceDocsFragment;
        }
    }

    @Override
    public int getItemCount() {
        return 2;
    }

    public void setFilterQuery(String query) {
        if (scannedDocsFragment != null && scannedDocsFragment.isAdded()) {
            scannedDocsFragment.setFilterQuery(query);
        }
        if (deviceDocsFragment != null && deviceDocsFragment.isAdded()) {
            deviceDocsFragment.setFilterQuery(query);
        }
    }

    public void scrollToTop(int position) {
        if (position == 0 && scannedDocsFragment != null) {
            scannedDocsFragment.scrollToTop();
        } else if (position == 1 && deviceDocsFragment != null) {
            deviceDocsFragment.scrollToTop();
        }
    }
}
