package io.github.androidpoet.tor

import kotlin.jvm.JvmOverloads

/**
 * A Tor operation failed. [message] carries Arti's explanation.
 *
 * The single-argument constructor must exist on the JVM: the native library
 * throws this exception from JNI with `(String)`.
 */
public class TorException
    @JvmOverloads
    constructor(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)
