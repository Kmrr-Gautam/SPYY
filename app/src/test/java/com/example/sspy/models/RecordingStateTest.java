package com.example.sspy.models;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingStateTest {
    @Test
    public void permitsNormalRecordingLifecycle() {
        assertTrue(RecordingState.IDLE.canTransitionTo(RecordingState.STARTING));
        assertTrue(RecordingState.STARTING.canTransitionTo(RecordingState.RECORDING));
        assertTrue(RecordingState.RECORDING.canTransitionTo(RecordingState.STOPPING));
        assertTrue(RecordingState.STOPPING.canTransitionTo(RecordingState.FINALIZING));
        assertTrue(RecordingState.FINALIZING.canTransitionTo(RecordingState.SAVED));
    }

    @Test
    public void permitsPermissionGateBeforeStarting() {
        assertTrue(RecordingState.IDLE.canTransitionTo(RecordingState.REQUESTING_PERMISSION));
        assertTrue(RecordingState.REQUESTING_PERMISSION.canTransitionTo(RecordingState.STARTING));
        assertTrue(RecordingState.REQUESTING_PERMISSION.canTransitionTo(RecordingState.IDLE));
    }

    @Test
    public void rejectsOverlappingOrOutOfOrderTransitions() {
        assertFalse(RecordingState.RECORDING.canTransitionTo(RecordingState.STARTING));
        assertFalse(RecordingState.FINALIZING.canTransitionTo(RecordingState.RECORDING));
        assertFalse(RecordingState.SAVED.canTransitionTo(RecordingState.RECORDING));
    }

    @Test
    public void activeStatesAreReportedConsistently() {
        assertTrue(RecordingState.STARTING.isActive());
        assertTrue(RecordingState.RECORDING.isActive());
        assertTrue(RecordingState.STOPPING.isActive());
        assertTrue(RecordingState.FINALIZING.isActive());
        assertFalse(RecordingState.IDLE.isActive());
        assertFalse(RecordingState.REQUESTING_PERMISSION.isActive());
        assertFalse(RecordingState.SAVED.isActive());
        assertFalse(RecordingState.ERROR.isActive());
    }
}
