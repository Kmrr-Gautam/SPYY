package com.example.sspy.services;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.example.sspy.R;
import com.example.sspy.models.RecordingState;
import com.example.sspy.notifications.RecordingNotificationManager;
import com.example.sspy.permissions.PermissionManager;
import com.example.sspy.storage.VideoStorageManager;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class RecordingService extends LifecycleService {
    public static final String ACTION_TOGGLE = "com.example.sspy.action.TOGGLE";
    public static final String ACTION_STOP = "com.example.sspy.action.STOP";
    private static final String TAG = "SSPY.RecordingService";

    public interface StateListener {
        void onStateChanged(RecordingState state, String message);
    }

    public final class LocalBinder extends Binder {
        public RecordingService getService() {
            return RecordingService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Executor mainExecutor = command -> ContextCompat.getMainExecutor(this).execute(command);

    private RecordingState state = RecordingState.IDLE;
    private Recording recording;
    private ProcessCameraProvider cameraProvider;
    private VideoStorageManager storageManager;
    private RecordingNotificationManager notificationManager;
    private StateListener stateListener;
    private boolean stopRequested;
    private boolean foregroundStarted;
    private String pendingFailureMessage;

    @Override
    public void onCreate() {
        super.onCreate();
        storageManager = new VideoStorageManager(this);
        notificationManager = new RecordingNotificationManager(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent == null) {
            stopSelf(startId);
            return Service.START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requestStop(startId);
        } else if (ACTION_TOGGLE.equals(action)) {
            if (state == RecordingState.STARTING || state == RecordingState.RECORDING) {
                requestStop(startId);
            } else if (state == RecordingState.STOPPING
                    || state == RecordingState.FINALIZING) {
                Log.i(TAG, "Ignoring toggle while finalization is in progress");
            } else {
                startRecording();
            }
        } else {
            stopSelf(startId);
        }
        return Service.START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return binder;
    }

    @Override
    public void onDestroy() {
        if (recording != null) {
            Log.w(TAG, "Service destroyed before recording finalization completed");
            try {
                recording.stop();
            } catch (IllegalStateException exception) {
                Log.w(TAG, "Recording was already stopped during service destruction", exception);
            }
            recording = null;
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        stateListener = null;
        super.onDestroy();
    }

    public RecordingState getState() {
        return state;
    }

    public void setStateListener(@Nullable StateListener listener) {
        stateListener = listener;
        if (listener != null) {
            listener.onStateChanged(state, null);
        }
    }

    private void startRecording() {
        if (state.isActive()) {
            Log.i(TAG, "Ignoring start request while a recording operation is active");
            return;
        }
        if (!PermissionManager.hasAllRequiredPermissions(this)
                || !PermissionManager.hasCameraAndMicrophonePermissions(this)) {
            failRecording(getString(R.string.error_permissions), null);
            return;
        }

        stopRequested = false;
        transitionTo(RecordingState.STARTING, getString(R.string.status_starting));
        try {
            startAsForeground();
        } catch (IllegalStateException | SecurityException exception) {
            failRecording(getString(R.string.error_foreground_service), exception);
            return;
        }

        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(getApplicationContext());
        providerFuture.addListener(() -> {
            if (state != RecordingState.STARTING && state != RecordingState.STOPPING) {
                return;
            }
            if (stopRequested) {
                completeCanceledStart();
                return;
            }
            try {
                cameraProvider = providerFuture.get();
                bindCameraAndStart();
            } catch (ExecutionException exception) {
                failRecording(getString(R.string.error_camera_start), exception.getCause());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failRecording(getString(R.string.error_camera_start), exception);
            }
        }, mainExecutor);
    }

    private void startAsForeground() {
        android.app.Notification notification =
                notificationManager.buildStartingNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                    RecordingNotificationManager.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(RecordingNotificationManager.NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private void bindCameraAndStart() {
        try {
            if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                failRecording(getString(R.string.error_rear_camera), null);
                return;
            }
            Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(
                            Quality.SD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build();
            VideoCapture<Recorder> videoCapture = VideoCapture.withOutput(recorder);
            cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    videoCapture);

            if (stopRequested) {
                completeCanceledStart();
                return;
            }

            MediaStoreOutputOptions outputOptions = storageManager.createPendingOutput();
            recording = recorder.prepareRecording(this, outputOptions)
                    .withAudioEnabled()
                    .start(mainExecutor, this::onVideoRecordEvent);
            if (stopRequested) {
                transitionTo(RecordingState.STOPPING, getString(R.string.status_stopping));
                stopActiveRecording();
            }
        } catch (CameraInfoUnavailableException | IllegalArgumentException
                 | IllegalStateException | SecurityException exception) {
            failRecording(getString(R.string.error_camera_start), exception);
        }
    }

    private void onVideoRecordEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Start) {
            if (state == RecordingState.STARTING) {
                transitionTo(RecordingState.RECORDING, getString(R.string.status_recording));
                if (!notificationManager.updateRecording()) {
                    pendingFailureMessage = getString(R.string.error_notification);
                    stopRequested = true;
                    transitionTo(RecordingState.STOPPING, getString(R.string.status_stopping));
                    stopActiveRecording();
                    return;
                }
                Toast.makeText(this, R.string.toast_recording_started, Toast.LENGTH_SHORT).show();
            }
            if (stopRequested) {
                if (state == RecordingState.RECORDING) {
                    transitionTo(RecordingState.STOPPING, getString(R.string.status_stopping));
                }
                stopActiveRecording();
            }
        } else if (event instanceof VideoRecordEvent.Finalize) {
            onRecordingFinalized((VideoRecordEvent.Finalize) event);
        }
    }

    private void onRecordingFinalized(VideoRecordEvent.Finalize event) {
        recording = null;
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        if (state == RecordingState.STARTING || state == RecordingState.RECORDING) {
            transitionTo(RecordingState.STOPPING, getString(R.string.status_stopping));
        }
        transitionTo(RecordingState.FINALIZING, getString(R.string.status_stopping));

        Uri outputUri = event.getOutputResults().getOutputUri();
        if (pendingFailureMessage != null) {
            storageManager.discard(outputUri);
            failRecording(pendingFailureMessage, event.getCause());
            return;
        }
        if (event.hasError()) {
            Log.e(TAG, "CameraX failed to finalize recording; code="
                    + event.getError(), event.getCause());
            storageManager.discard(outputUri);
            failRecording(getString(R.string.error_finalize), event.getCause());
            return;
        }

        try {
            if (!storageManager.publishCompletedVideo(outputUri)) {
                storageManager.discard(outputUri);
                failRecording(getString(R.string.error_publish), null);
                return;
            }
        } catch (SecurityException exception) {
            storageManager.discard(outputUri);
            failRecording(getString(R.string.error_publish), exception);
            return;
        }

        transitionTo(RecordingState.SAVED, getString(R.string.status_saved));
        Toast.makeText(this, R.string.toast_recording_saved, Toast.LENGTH_SHORT).show();
        finishService();
    }

    private void requestStop(int startId) {
        if (state == RecordingState.STARTING) {
            stopRequested = true;
            transitionTo(RecordingState.STOPPING, getString(R.string.status_stopping));
            if (recording != null) {
                stopActiveRecording();
            }
        } else if (state == RecordingState.RECORDING) {
            stopRequested = true;
            transitionTo(RecordingState.STOPPING, getString(R.string.status_stopping));
            stopActiveRecording();
        } else if (state == RecordingState.IDLE
                || state == RecordingState.SAVED
                || state == RecordingState.ERROR) {
            stopSelf(startId);
        }
    }

    private void stopActiveRecording() {
        if (recording == null) {
            return;
        }
        try {
            recording.stop();
        } catch (IllegalStateException exception) {
            failRecording(getString(R.string.error_stop), exception);
        }
    }

    private void completeCanceledStart() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        transitionTo(RecordingState.IDLE, getString(R.string.status_ready));
        Toast.makeText(this, R.string.toast_recording_not_started, Toast.LENGTH_SHORT).show();
        finishService();
    }

    private void failRecording(String message, @Nullable Throwable cause) {
        if (cause == null) {
            Log.e(TAG, message);
        } else {
            Log.e(TAG, message, cause);
        }
        if (state != RecordingState.ERROR && state.canTransitionTo(RecordingState.ERROR)) {
            transitionTo(RecordingState.ERROR, message);
        } else {
            notifyListener(RecordingState.ERROR, message);
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        recording = null;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finishService();
    }

    private void finishService() {
        if (foregroundStarted) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        } else {
            notificationManager.cancel();
        }
        stopSelf();
    }

    private void transitionTo(RecordingState next, String message) {
        if (!state.canTransitionTo(next)) {
            Log.e(TAG, "Rejected invalid state transition " + state + " -> " + next);
            return;
        }
        state = next;
        notifyListener(next, message);
    }

    private void notifyListener(RecordingState newState, @Nullable String message) {
        if (stateListener != null) {
            stateListener.onStateChanged(newState, message);
        }
    }
}
