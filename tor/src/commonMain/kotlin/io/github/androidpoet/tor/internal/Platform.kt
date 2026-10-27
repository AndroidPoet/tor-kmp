package io.github.androidpoet.tor.internal

/** Whether this platform lets a process launch another, which pluggable transports need. */
internal expect val supportsPluggableTransports: Boolean

/** True where paths are Windows paths, so `/x` is drive-relative rather than absolute. */
internal expect val windowsPaths: Boolean
