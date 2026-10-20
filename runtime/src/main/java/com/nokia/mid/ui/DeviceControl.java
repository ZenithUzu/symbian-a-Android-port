package com.nokia.mid.ui;

import j2a.core.Host;

public class DeviceControl {
    private DeviceControl() {}

    public static void setLights(int num, int level) {}

    public static void flashLights(long duration) {}

    public static void startVibra(int freq, long duration) {
        if (freq < 0 || freq > 100 || duration < 0) throw new IllegalArgumentException();
        Host.vibrate((int) Math.min(Integer.MAX_VALUE, duration));
    }

    public static void stopVibra() {
        Host.vibrate(0);
    }
}
