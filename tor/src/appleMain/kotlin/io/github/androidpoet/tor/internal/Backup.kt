package io.github.androidpoet.tor.internal

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/** Sets `NSURLIsExcludedFromBackupKey`, which iCloud and Time Machine honour. A Kotlin Boolean crosses to Objective-C as an NSNumber. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun excludeFromBackup(directory: String) {
    NSURL.fileURLWithPath(directory).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
}
