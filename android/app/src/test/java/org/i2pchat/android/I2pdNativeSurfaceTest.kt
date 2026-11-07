package org.i2pchat.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * libi2pd.so 3.0.0 registers these methods in JNI_OnLoad. A renamed or
 * non-static method makes Android fail the load with "JNI_OnLoad failed".
 * The class is not initialized, so this does not load the native library.
 */
class I2pdNativeSurfaceTest {
    @Test
    fun methodsMatchI2pd300Bridge() {
        val loader = javaClass.classLoader
        val nativeClass = Class.forName(
            "org.purplei2p.i2pd.nativelib.I2pdNative",
            false,
            loader,
        )
        val fatal = Class.forName(
            "org.purplei2p.i2pd.nativelib.FatalHandler",
            false,
            loader,
        )
        val expected = listOf(
            "version" to arrayOf(),
            "abi" to arrayOf(),
            "setFatalHandler" to arrayOf(fatal),
            "init" to arrayOf(String::class.java, Array<String>::class.java, String::class.java),
            "start" to arrayOf(),
            "stop" to arrayOf(),
            "status" to arrayOf(LongArray::class.java),
            "setAcceptsTransit" to arrayOf(Boolean::class.javaPrimitiveType),
            "reloadTunnels" to arrayOf(),
            "setOnline" to arrayOf(Boolean::class.javaPrimitiveType),
            "option" to arrayOf(String::class.java),
            "readLog" to arrayOf(
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                LongArray::class.java,
            ),
            "describeOptions" to arrayOf(),
        )
        for ((name, params) in expected) {
            val method = nativeClass.getDeclaredMethod(name, *params)
            assertTrue("$name must be static", Modifier.isStatic(method.modifiers))
            assertTrue("$name must be native", Modifier.isNative(method.modifiers))
        }
        assertEquals(13, expected.size)
        val onFatal = fatal.getDeclaredMethod("onFatal", String::class.java)
        assertEquals(String::class.java, onFatal.parameterTypes.single())
    }
}
