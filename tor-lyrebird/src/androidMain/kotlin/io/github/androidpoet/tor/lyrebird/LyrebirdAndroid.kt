package io.github.androidpoet.tor.lyrebird

import android.content.Context
import io.github.androidpoet.tor.PluggableTransport
import java.io.File

/**
 * The bundled lyrebird as installed with your app, as a [PluggableTransport].
 *
 * The binary ships as `liblyrebird.so` so the installer places it in the app's
 * native-library directory, the only app-writable location Android still allows
 * executing from. That requires native libraries to be extracted at install
 * time, which only the app module can ask for: set
 * `android { packaging { jniLibs { useLegacyPackaging = true } } }` in its build
 * script. A library manifest's `extractNativeLibs` is not enough; AGP packages
 * from the app's own setting.
 *
 * @throws IllegalStateException if the binary is missing (the app did not enable
 * legacy jniLibs packaging) or not executable.
 */
public fun Lyrebird.transport(context: Context): PluggableTransport {
    val binary = File(context.applicationInfo.nativeLibraryDir, LIBRARY_NAME)
    check(binary.exists()) {
        "$LIBRARY_NAME is not in ${binary.parent}: the app must extract native libraries at install time; " +
            "add android { packaging { jniLibs { useLegacyPackaging = true } } } to the app module"
    }
    check(binary.canExecute() || binary.setExecutable(true, false)) { "${binary.absolutePath} is not executable" }
    return transportAt(binary.absolutePath)
}

private const val LIBRARY_NAME = "liblyrebird.so"
