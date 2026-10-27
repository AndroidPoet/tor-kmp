package io.github.androidpoet.tor

/** A Tor operation failed. [message] carries Arti's explanation. */
public class TorException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
