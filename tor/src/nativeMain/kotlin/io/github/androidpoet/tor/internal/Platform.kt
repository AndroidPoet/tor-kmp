package io.github.androidpoet.tor.internal

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

/** iOS sandboxes forbid fork/exec; every other native target can run a transport binary. */
@OptIn(ExperimentalNativeApi::class)
internal actual val supportsPluggableTransports: Boolean = Platform.osFamily != OsFamily.IOS

@OptIn(ExperimentalNativeApi::class)
internal actual val windowsPaths: Boolean = Platform.osFamily == OsFamily.WINDOWS
