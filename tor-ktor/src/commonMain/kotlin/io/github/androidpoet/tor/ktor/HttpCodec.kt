package io.github.androidpoet.tor.ktor

import io.ktor.client.engine.mergeHeaders
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HeadersImpl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.ConnectionOptions
import io.ktor.http.cio.HttpHeadersMap
import io.ktor.http.cio.RequestResponseBuilder
import io.ktor.http.cio.encodeChunked
import io.ktor.http.cio.parseHttpBody
import io.ktor.http.cio.parseResponse
import io.ktor.http.content.OutgoingContent
import io.ktor.http.hostWithPort
import io.ktor.http.supportsRequestBody
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.copyAndClose
import io.ktor.utils.io.invokeOnCompletion
import io.ktor.utils.io.join
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writePacket
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.EOFException

// HTTP/1.1 over a TorConnection, built on Ktor's own codec (ktor-http-cio). The
// shape follows Ktor's CIO engine so request and response semantics match it.

/** Writes the request line, headers and body of [request] to [output]. */
@OptIn(InternalAPI::class)
internal suspend fun writeRequest(
    request: HttpRequestData,
    output: ByteWriteChannel,
    scope: CoroutineScope,
) {
    val body = request.body.unwrapped()
    if (body is OutgoingContent.ProtocolUpgrade) {
        throw UnsupportedOperationException("tor-ktor does not support protocol upgrades (WebSockets) yet")
    }
    val contentLength = request.headers[HttpHeaders.ContentLength] ?: body.contentLength?.toString()
    val chunked = contentLength == null && body !is OutgoingContent.NoContent

    writeHead(request, body, contentLength, chunked, output)
    if (body is OutgoingContent.NoContent) return
    writeBody(body, chunked, output, scope)
}

@OptIn(InternalAPI::class)
private suspend fun writeHead(
    request: HttpRequestData,
    body: OutgoingContent,
    contentLength: String?,
    chunked: Boolean,
    output: ByteWriteChannel,
) {
    val builder = RequestResponseBuilder()
    try {
        val url = request.url
        val path = url.encodedPathAndQuery.let { if (it.startsWith("/")) it else "/$it" }
        builder.requestLine(request.method, path, HttpProtocolVersion.HTTP_1_1.toString())
        if (!request.headers.contains(HttpHeaders.Host)) {
            builder.headerLine(HttpHeaders.Host, if (url.protocol.defaultPort == url.port) url.host else url.hostWithPort)
        }
        if (contentLength != null && (request.method.supportsRequestBody || body !is OutgoingContent.NoContent)) {
            builder.headerLine(HttpHeaders.ContentLength, contentLength)
        }
        mergeHeaders(request.headers, request.body) { name, value ->
            // Framing headers are decided here, not copied; Expect: 100-continue is not supported.
            if (name !in FRAMING_HEADERS) builder.headerLine(name, value)
        }
        if (chunked) builder.headerLine(HttpHeaders.TransferEncoding, "chunked")
        builder.emptyLine()
        output.writePacket(builder.build())
        output.flush()
    } finally {
        builder.release()
    }
}

private suspend fun writeBody(
    body: OutgoingContent,
    chunked: Boolean,
    output: ByteWriteChannel,
    scope: CoroutineScope,
) {
    val encoder = if (chunked) encodeChunked(output, scope.coroutineContext) else null
    val channel = encoder?.channel ?: output
    try {
        when (body) {
            is OutgoingContent.ByteArrayContent -> channel.writeFully(body.bytes())
            is OutgoingContent.ReadChannelContent -> body.readFrom().copyAndClose(channel)
            is OutgoingContent.WriteChannelContent -> body.writeTo(channel)
            else -> error("unreachable: ${body::class}")
        }
    } finally {
        channel.flush()
        if (encoder != null) {
            encoder.channel.flushAndClose()
            encoder.join()
        }
        output.flush()
    }
}

/**
 * Reads the status line, headers and body from [input]. [onDone] is called with
 * `true` once the body has been fully read and the connection may serve another
 * request, or `false` if the connection must be closed.
 */
internal suspend fun readResponse(
    requestTime: GMTDate,
    request: HttpRequestData,
    input: ByteReadChannel,
    scope: CoroutineScope,
    onDone: (reusable: Boolean) -> Unit,
): HttpResponseData {
    val raw =
        parseResponse(input)
            ?: throw EOFException("The server closed the connection before sending a response")
    try {
        val status = HttpStatusCode(raw.status, raw.statusText.toString())
        val contentLength = raw.headers[HttpHeaders.ContentLength]?.toString()?.toLongOrNull() ?: -1L
        val transferEncoding = raw.headers[HttpHeaders.TransferEncoding]?.toString()
        val connection = ConnectionOptions.parse(raw.headers[HttpHeaders.Connection])
        val version = HttpProtocolVersion.parse(raw.version)
        val headers = HeadersImpl(raw.headers.toMap())

        val hasBody =
            request.method != HttpMethod.Head &&
                status != HttpStatusCode.NotModified &&
                status != HttpStatusCode.NoContent &&
                status.value / INFORMATIONAL_HUNDREDS != 1
        // A body delimited only by connection close cannot be followed by another request,
        // and one with conflicting framing might end anywhere, so neither connection is reused.
        val chunkedBody = transferEncoding.equals("chunked", ignoreCase = true)
        val framed = chunkedBody || (transferEncoding == null && contentLength >= 0L)
        val reusable = version == HttpProtocolVersion.HTTP_1_1 && connection?.close != true && (!hasBody || framed)

        val body: ByteReadChannel =
            if (!hasBody) {
                onDone(reusable)
                ByteReadChannel.Empty
            } else {
                val parser =
                    scope.writer(autoFlush = true) {
                        parseHttpBody(version, contentLength, transferEncoding, connection, input, channel)
                    }
                parser.invokeOnCompletion { cause -> onDone(cause == null && reusable) }
                parser.channel
            }
        return HttpResponseData(status, requestTime, headers, version, body, scope.coroutineContext)
    } finally {
        raw.release()
    }
}

private fun OutgoingContent.unwrapped(): OutgoingContent =
    if (this is OutgoingContent.ContentWrapper) delegate().unwrapped() else this

private fun HttpHeadersMap.toMap(): Map<String, List<String>> {
    val result = mutableMapOf<String, MutableList<String>>()
    for (offset in offsets()) {
        result.getOrPut(nameAtOffset(offset).toString()) { mutableListOf() }.add(valueAtOffset(offset).toString())
    }
    return result
}

private const val INFORMATIONAL_HUNDREDS = 100
private val FRAMING_HEADERS = setOf(HttpHeaders.ContentLength, HttpHeaders.TransferEncoding, HttpHeaders.Expect)
