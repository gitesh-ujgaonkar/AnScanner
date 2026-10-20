package com.anscanner.app;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.anscanner.app.ui.camera.CameraActivity;
import com.anscanner.app.util.AppUpdateHelper;

/**
 * MainActivity serves as an entry point that routes to {@link CameraActivity},
 * forwarding any incoming intent extras such as {@link AppUpdateHelper#EXTRA_TRIGGER_UPDATE}.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = new Intent(this, CameraActivity.class);
        if (getIntent() != null && getIntent().getExtras() != null) {
            intent.putExtras(getIntent().getExtras());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }
}
