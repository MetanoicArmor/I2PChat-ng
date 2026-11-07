package org.purplei2p.i2pd.nativelib

/** Receives i2pd fatal errors. Called synchronously from a native thread, possibly right before exit(). */
fun interface FatalHandler {
    fun onFatal(message: String)
}

/**
 * JNI surface of PurpleI2P libi2pd.so 3.0.0.
 *
 * Methods are registered in JNI_OnLoad (i2pd-android native/jni/jni_bridge.cpp).
 * The class name and these signatures must stay in sync with kMethods there:
 * FindClass looks up this class, and a missing method makes Android report
 * "JNI_OnLoad failed".
 *
 * i2pd keeps global state that cannot be re-initialized, so [init] may be called
 * only once per process. A restart means a new process.
 */
object I2pdNative {
    init {
        System.loadLibrary("i2pd")
    }

    @JvmStatic
    external fun version(): String

    @JvmStatic
    external fun abi(): String

    @JvmStatic
    external fun setFatalHandler(handler: FatalHandler?)

    /** Parses command line and config. Returns null on success, otherwise an error message. */
    @JvmStatic
    external fun init(dataDir: String, args: Array<String>, language: String): String?

    /** Starts the router. Returns null on success, otherwise an error message. */
    @JvmStatic
    external fun start(): String?

    /** Stops the router, blocking. */
    @JvmStatic
    external fun stop()

    /** Fills [out] (at least [Status.SIZE] longs). Returns false if the router is not running. */
    @JvmStatic
    external fun status(out: LongArray): Boolean

    @JvmStatic
    external fun setAcceptsTransit(accept: Boolean)

    @JvmStatic
    external fun reloadTunnels()

    @JvmStatic
    external fun setOnline(online: Boolean)

    @JvmStatic
    external fun option(name: String): String?

    @JvmStatic
    external fun readLog(sinceSeq: Long, max: Int, outFirstSeq: LongArray): Array<String>

    @JvmStatic
    external fun describeOptions(): String?

    /** Indexes of [status], kept in sync with StatusIndex in jni_bridge.cpp. */
    object Status {
        const val SERVICES = 22
        const val SIZE = 24
        const val SERVICE_SAM = 1L shl 2

        fun samRunning(raw: LongArray): Boolean {
            if (raw.size <= SERVICES) return false
            return raw[SERVICES] and SERVICE_SAM != 0L
        }
    }
}
