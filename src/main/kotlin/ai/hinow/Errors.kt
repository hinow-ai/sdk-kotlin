package ai.hinow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Base class for every error this SDK throws. Catching it catches everything;
 * catch a subclass to react to one specific failure.
 */
open class HinowException(
    message: String,
    /** HTTP status, when the failure came from the API. */
    val statusCode: Int? = null,
    /** The error code the API reported, when it sent one. */
    val errorCode: String? = null,
    /** The error type the API reported, e.g. "invalid_request_error". */
    val errorType: String? = null,
    /** The raw response body, kept for logging. */
    val responseBody: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 401 — the key is missing, malformed or revoked. Retrying will not help.
 * A valid key starts with `hi_`.
 */
class AuthenticationException(
    message: String, statusCode: Int, errorCode: String?, errorType: String?, body: String?,
) : HinowException(message, statusCode, errorCode, errorType, body)

/**
 * 403 — the key is valid but not allowed to do this, usually a model or
 * endpoint the plan does not include.
 */
class PermissionException(
    message: String, statusCode: Int, errorCode: String?, errorType: String?, body: String?,
) : HinowException(message, statusCode, errorCode, errorType, body)

/**
 * 404 — no such model, file, assistant or thread. On a model the usual cause is
 * a missing namespace: it is `hinow/himax`, not `himax`.
 */
class NotFoundException(
    message: String, statusCode: Int, errorCode: String?, errorType: String?, body: String?,
) : HinowException(message, statusCode, errorCode, errorType, body)

/** 400 or 422 — the request itself is wrong. The message names the field. */
class InvalidRequestException(
    message: String, statusCode: Int, errorCode: String?, errorType: String?, body: String?,
) : HinowException(message, statusCode, errorCode, errorType, body)

/**
 * 429 — too many requests, or the account ran out of credit. The client already
 * retried; seeing this means the retries failed too.
 */
class RateLimitException(
    message: String, statusCode: Int, errorCode: String?, errorType: String?, body: String?,
) : HinowException(message, statusCode, errorCode, errorType, body)

/** 402 — the account has no credit left. */
class InsufficientBalanceException(
    message: String, statusCode: Int, errorCode: String?, errorType: String?, body: String?,
) : HinowException(message, statusCode, errorCode, errorType, body)

/** 5xx — the failure is on the API side. Worth retrying later. */
class ServerException(
    message: String, statusCode: Int, errorCode: String?, errorType: String?, body: String?,
) : HinowException(message, statusCode, errorCode, errorType, body)

/**
 * The request never got an answer: DNS, TLS, a dropped socket or a timeout.
 * Nothing reached the API, so nothing was charged.
 */
class ConnectionException(message: String, cause: Throwable? = null) :
    HinowException(message, cause = cause)

/** A job or a run did not settle in time. */
class TimeoutException(message: String) : HinowException(message)

/** Build the right subclass from a status and the response body. */
internal fun errorFor(status: Int, body: String): HinowException {
    var message = body
    var code: String? = null
    var type: String? = null

    try {
        val root = Json.parseToJsonElement(body).jsonObject
        val error = (root["error"] as? JsonObject) ?: root

        message = error["message"]?.jsonPrimitive?.contentOrNullSafe()
            ?: error["detail"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: body
        code = error["code"]?.jsonPrimitive?.contentOrNullSafe()
        type = error["type"]?.jsonPrimitive?.contentOrNullSafe()
    } catch (ignored: Exception) {
        // Not JSON. The raw body is already the message.
    }

    if (message.isBlank()) {
        message = "HTTP $status"
    }

    return when {
        status == 401 -> AuthenticationException(message, status, code, type, body)
        status == 402 -> InsufficientBalanceException(message, status, code, type, body)
        status == 403 -> PermissionException(message, status, code, type, body)
        status == 404 -> NotFoundException(message, status, code, type, body)
        status == 429 -> RateLimitException(message, status, code, type, body)
        status == 400 || status == 422 -> InvalidRequestException(message, status, code, type, body)
        status >= 500 -> ServerException(message, status, code, type, body)
        else -> HinowException(message, status, code, type, body)
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
