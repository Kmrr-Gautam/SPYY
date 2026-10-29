package com.example.sspy.activities;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.sspy.R;
import com.example.sspy.models.RecordingState;
import com.example.sspy.permissions.PermissionManager;
import com.example.sspy.services.RecordingService;

import java.util.Map;

public class MainActivity extends ComponentActivity {
    private static final String PREFERENCES_NAME = "permission_status";
    private static final String KEY_PERMISSION_REQUESTED = "requested";
    private static final long FINISH_TIMEOUT_MS = 10000L;

    private ActivityResultLauncher<String[]> permissionLauncher;
    private RecordingService recordingService;
    private boolean bound;
    private boolean permissionRequestInProgress;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = this::finish;

    private final RecordingService.StateListener stateListener = this::onServiceStateChanged;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            RecordingService.LocalBinder binder = (RecordingService.LocalBinder) service;
            recordingService = binder.getService();
            recordingService.setStateListener(stateListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            recordingService = null;
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::onPermissionResult);

        timeoutHandler.postDelayed(timeoutRunnable, FINISH_TIMEOUT_MS);
        handleLauncherToggle();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLauncherToggle();
    }

    @Override
    protected void onDestroy() {
        timeoutHandler.removeCallbacks(timeoutRunnable);
        unbindFromService();
        super.onDestroy();
    }

    private void handleLauncherToggle() {
        if (PermissionManager.hasAllRequiredPermissions(this)) {
            startServiceAction(RecordingService.ACTION_TOGGLE);
        } else {
            requestMissingPermissions();
        }
    }

    private void requestMissingPermissions() {
        if (permissionRequestInProgress) {
            return;
        }
        String[] permissions = PermissionManager.getRequiredPermissions();
        if (hasPermanentlyDeniedPermission(permissions)) {
            Toast.makeText(this, R.string.status_permission_needed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        SharedPreferences preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        preferences.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply();
        permissionRequestInProgress = true;
        permissionLauncher.launch(permissions);
    }

    private void onPermissionResult(Map<String, Boolean> results) {
        permissionRequestInProgress = false;
        if (PermissionManager.hasAllRequiredPermissions(this)) {
            startServiceAction(RecordingService.ACTION_TOGGLE);
        } else {
            Toast.makeText(this, R.string.status_permission_needed, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private boolean hasPermanentlyDeniedPermission(String[] permissions) {
        SharedPreferences preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_PERMISSION_REQUESTED, false)) {
            return false;
        }
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                    && !shouldShowRequestPermissionRationale(permission)) {
                return true;
            }
        }
        return false;
    }

    private void startServiceAction(String action) {
        Intent serviceIntent = new Intent(this, RecordingService.class).setAction(action);
        try {
            ContextCompat.startForegroundService(this, serviceIntent);
            bindToService();
        } catch (IllegalStateException | SecurityException exception) {
            Toast.makeText(this, R.string.error_foreground_service, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void bindToService() {
        if (bound) {
            return;
        }
        Intent serviceIntent = new Intent(this, RecordingService.class);
        bound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void unbindFromService() {
        if (bound) {
            if (recordingService != null) {
                recordingService.setStateListener(null);
            }
            unbindService(serviceConnection);
            bound = false;
            recordingService = null;
        }
    }

    private void onServiceStateChanged(RecordingState state, @Nullable String message) {
        switch (state) {
            case RECORDING:
            case SAVED:
            case ERROR:
            case IDLE:
                finish();
                break;
            case STARTING:
            case STOPPING:
            case FINALIZING:
            case REQUESTING_PERMISSION:
            default:
                break;
        }
    }
}
