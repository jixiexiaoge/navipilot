package com.navipilot.amap2tesla.privileged;

import android.view.Surface;

interface IPrivilegedDisplayService {
    String probeCapabilities();
    int createVirtualDisplay(String name, int width, int height, int densityDpi, float refreshRate, in Surface surface);
    boolean launchPackage(int displayId, String packageName);
    int ensurePackageOnDisplay(int displayId, String packageName);
    boolean injectTouch(int displayId, int gestureId, int action, float x, float y, int pointerId);
    void cancelTouch(int displayId, int gestureId);
    boolean injectKey(int displayId, int keyCode);
    void releaseDisplay(int displayId);
}
