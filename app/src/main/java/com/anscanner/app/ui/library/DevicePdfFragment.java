package com.anscanner.app.ui.library;

/**
 * Alias class for {@link DeviceDocsFragment} supporting both "DevicePdfFragment"
 * and "DeviceDocsFragment" naming conventions.
 */
public class DevicePdfFragment extends DeviceDocsFragment {
    public static DevicePdfFragment newInstance() {
        return new DevicePdfFragment();
    }
}
