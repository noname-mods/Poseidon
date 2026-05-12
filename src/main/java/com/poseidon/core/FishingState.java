package com.poseidon.core;

public enum FishingState {
    /** Poseidon is toggled off or no rod is cast. */
    IDLE,
    /** Bobber is in the water, watching for the !!! signal entity to appear near it. */
    WAITING,
    /** !!! signal detected near the bobber — human reaction delay is counting down. */
    BITING,
    /** Reel-in command has been sent, waiting for the bobber to disappear. */
    REELING
}
