package com.ricoh.camera.sdk.wireless.api;

/* Generated compile-time stub - not packaged. */
public interface SelfTimer {
    enum State { IDLE, BUSY2S, BUSY10S, BUSY12S, TIME }
    State getCountDownState();
}
