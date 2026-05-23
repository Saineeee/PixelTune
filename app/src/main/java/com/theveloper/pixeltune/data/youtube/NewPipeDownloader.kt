package com.theveloper.pixeltune.data.youtube

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import javax.inject.Inject

class NewPipeDownloader @Inject constructor(
    private val client: OkHttpClient
) : Downloader() {

    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = Request.Builder().url(url)

        val contentTypeHeader = headers.entries.find { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
        val mediaType = contentTypeHeader?.toMediaTypeOrNull()

        val methodNeedsBody = httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH"
        val methodCannotHaveBody = httpMethod == "GET" || httpMethod == "HEAD" || httpMethod == "OPTIONS"

        if (dataToSend != null && !methodCannotHaveBody) {
            requestBuilder.method(httpMethod, dataToSend.toRequestBody(mediaType))
        } else if (methodNeedsBody) {
            requestBuilder.method(httpMethod, ByteArray(0).toRequestBody(mediaType))
        } else {
            requestBuilder.method(httpMethod, null)
        }

        headers.forEach { (key, values) ->
            if (key.equals("Content-Type", ignoreCase = true)) return@forEach
            
            // CRITICAL SOUNDCLOUD FIX: We MUST filter out Accept-Encoding.
            // When we let OkHttp handle this internally, it will perform transparent
            // GZIP decompression for us. This provides NewPipe with the uncompressed HTML 
            // text it needs to extract the SoundCloud client_id.
            if (key.equals("Accept-Encoding", ignoreCase = true)) return@forEach
            
            if (values.size == 1) {
                requestBuilder.header(key, values[0])
            } else {
                values.forEach { value ->
                    requestBuilder.addHeader(key, value)
                }
            }
        }

        val okHttpRequest = requestBuilder.build()
        val response = client.newCall(okHttpRequest).execute()

        // CRITICAL YOUTUBE FIX: Fetch raw bytes! Do not use .string()!
        // Because we bypassed Accept-Encoding above, OkHttp has already transparently 
        // decompressed the stream. Returning .bytes() safely supports both text 
        // (SoundCloud HTML/JSON) and binary formats (YouTube Protobufs).
        val responseBytes = response.body?.bytes() ?: ByteArray(0)
        
        val responseHeaders = mutableMapOf<String, List<String>>()
        response.headers.names().forEach { name ->
            responseHeaders[name] = response.headers.values(name)
        }

        // Pass the raw byte[] so NewPipe isn't fed corrupted UTF-8 string data
        return ExtractorResponse(
            response.code,
            response.message,
            responseHeaders,
            responseBytes,
            response.request.url.toString()
        )
    }
}
