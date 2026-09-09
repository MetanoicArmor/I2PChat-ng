package org.purplei2p.i2pd;

/** JNI surface of the official PurpleI2P libi2pd.so (in-process daemon). */
public final class I2PD_JNI {
    private I2PD_JNI() {}

    public static void loadLibraries() {
        System.loadLibrary("i2pd");
    }

    /** @return "ok" or an error string */
    public static native String startDaemon();

    public static native void stopDaemon();

    public static native void startAcceptingTunnels();

    public static native void stopAcceptingTunnels();

    public static native void reloadTunnelsConfigs();

    public static native void setDataDir(String jdataDir);

    public static native void setLanguage(String jlanguage);

    public static native String getDataDir();

    public static native boolean getSAMState();

    public static native void onNetworkStateChanged(boolean isConnected);
}
