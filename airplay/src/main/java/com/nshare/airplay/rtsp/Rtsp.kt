package com.nshare.airplay.rtsp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.TreeMap

class RtspRequest(
    val method: String,
    val uri: String,
    val protocol: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name]
    val contentType: String? get() = header("Content-Type")
    val cseq: String? get() = header("CSeq")
    val path: String get() = uri.substringBefore('?')

    override fun toString(): String = "$method $uri $protocol (${body.size} bytes)"
}

class RtspResponse(val protocol: String) {
    var status: Int = 200
    var reason: String = "OK"
    val headers = LinkedHashMap<String, String>()
    var body: ByteArray = EMPTY
    /** Close the connection after writing this response. */
    var disconnect = false

    fun status(code: Int, text: String) {
        status = code
        reason = text
    }

    fun header(name: String, value: String) {
        headers[name] = value
    }

    fun body(contentType: String, data: ByteArray) {
        headers["Content-Type"] = contentType
        body = data
    }

    fun writeTo(out: OutputStream) {
        val sb = StringBuilder()
        sb.append(protocol).append(' ').append(status).append(' ').append(reason).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n\r\n")
        val head = sb.toString().toByteArray(StandardCharsets.UTF_8)
        val all = ByteArray(head.size + body.size)
        System.arraycopy(head, 0, all, 0, head.size)
        System.arraycopy(body, 0, all, head.size, body.size)
        out.write(all)
        out.flush()
    }

    companion object {
        val EMPTY = ByteArray(0)
    }
}

/** Reads RTSP/HTTP style requests from a stream. Returns null on clean EOF. */
class RtspReader(private val input: InputStream) {
    private val line = ByteArrayOutputStream(256)

    fun read(): RtspRequest? {
        var requestLine: String
        do {
            requestLine = readLine() ?: return null
        } while (requestLine.isEmpty())

        val parts = requestLine.split(' ')
        if (parts.size < 3) throw IOException("bad request line: $requestLine")
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        var count = 0
        while (true) {
            val h = readLine() ?: throw IOException("eof in headers")
            if (h.isEmpty()) break
            if (++count > MAX_HEADERS) throw IOException("too many headers")
            val idx = h.indexOf(':')
            if (idx <= 0) continue
            headers[h.substring(0, idx).trim()] = h.substring(idx + 1).trim()
        }
        val len = headers["Content-Length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) throw IOException("bad content length $len")
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) throw IOException("eof in body")
            read += n
        }
        return RtspRequest(parts[0], parts[1], parts[2], headers, body)
    }

    private fun readLine(): String? {
        line.reset()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8.name())
            if (c == '\n'.code) break
            if (c != '\r'.code) line.write(c)
            if (line.size() > MAX_LINE) throw IOException("line too long")
        }
        return line.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val MAX_HEADERS = 64
        private const val MAX_LINE = 8192
        private const val MAX_BODY = 4 * 1024 * 1024
    }
}
