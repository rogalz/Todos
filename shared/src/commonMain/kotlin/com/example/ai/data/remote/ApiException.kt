package com.example.ai.data.remote

/**
 * The server answered, but not with success.
 *
 * @property status the HTTP status code.
 * @property body the response body (the server explains a 400 in plain text), kept for the log.
 */
class ApiException(val status: Int, val body: String) :
    Exception("HTTP $status: ${body.take(MAX_BODY_IN_MESSAGE)}") {

    /**
     * `true` when the server refuses this very request and will keep refusing it however often it
     * is retried (a 4xx, e.g. validation). 408 (request timeout) and 429 (too many requests) are
     * 4xx too but say "try again later", so they are not rejections. A 5xx means the server is in
     * trouble, not that the request is wrong.
     */
    val isRejection: Boolean
        get() = status in 400..499 && status != REQUEST_TIMEOUT && status != TOO_MANY_REQUESTS

    private companion object {
        const val MAX_BODY_IN_MESSAGE = 300
        const val REQUEST_TIMEOUT = 408
        const val TOO_MANY_REQUESTS = 429
    }
}
