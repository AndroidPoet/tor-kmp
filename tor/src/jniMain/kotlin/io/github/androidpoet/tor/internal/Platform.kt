package io.github.androidpoet.tor.internal

internal actual val supportsPluggableTransports: Boolean = true

internal actual val windowsPaths: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
