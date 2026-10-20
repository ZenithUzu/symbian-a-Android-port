package com.samsung.util;

import j2a.core.Host;

public class Vibration {
    private Vibration() {}

    public static boolean isSupported() { return true; }

    public static void start(int duration, int strength) {
        Host.vibrate(duration * 1000);
    }

    public static void stop() {
        Host.vibrate(0);
    }
}
