package com.example.sspy.models;

public enum RecordingState {
    IDLE,
    REQUESTING_PERMISSION,
    STARTING,
    RECORDING,
    STOPPING,
    FINALIZING,
    SAVED,
    ERROR;

    public boolean canTransitionTo(RecordingState next) {
        switch (this) {
            case IDLE:
                return next == REQUESTING_PERMISSION || next == STARTING || next == ERROR;
            case REQUESTING_PERMISSION:
                return next == STARTING || next == IDLE || next == ERROR;
            case STARTING:
                return next == RECORDING || next == STOPPING
                        || next == FINALIZING || next == IDLE || next == ERROR;
            case RECORDING:
                return next == STOPPING || next == ERROR;
            case STOPPING:
                return next == FINALIZING || next == IDLE || next == ERROR;
            case FINALIZING:
                return next == SAVED || next == ERROR;
            case SAVED:
            case ERROR:
                return next == STARTING || next == IDLE;
            default:
                return false;
        }
    }

    public boolean isActive() {
        return this == STARTING || this == RECORDING
                || this == STOPPING || this == FINALIZING;
    }
}
