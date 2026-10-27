package io.github.androidpoet.tor.internal

/**
 * Keeps [directory] out of the platform's cloud backup where the platform has a
 * per-directory switch (iOS and macOS). The directory holds the client's guard
 * choice and keys; restored onto another device they link it to this one. Best
 * effort, never throws. A no-op elsewhere: Android decides backup in the app's
 * manifest, which is why the docs point it at `noBackupFilesDir`.
 */
internal expect fun excludeFromBackup(directory: String)
