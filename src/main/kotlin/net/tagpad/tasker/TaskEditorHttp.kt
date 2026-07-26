package net.tagpad.tasker

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.impl.BaseRepository
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.RequestBuilder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Shared plumbing for the REST calls the tasks API can't make for us. */

internal const val JSON_CONTENT_TYPE = "application/json"

/**
 * Writes [payload] and drains the response.
 *
 * Reading is what makes failure visible: [HttpRequests] turns a non-2xx into an `HttpStatusException`
 * carrying the server's own message, and that is what reaches the error balloon.
 */
internal fun RequestBuilder.sendJson(payload: JsonObject, headers: Map<String, String>): String =
    withHeaders(headers).connect { request ->
        request.write(payload.toString())
        request.readString()
    }

/** GETs a JSON object — for fields a provider's [com.intellij.tasks.Task] doesn't carry. */
internal fun readJsonObject(url: String, headers: Map<String, String>): JsonObject =
    HttpRequests.request(url).withHeaders(headers).connect { request ->
        JsonParser.parseString(request.readString()).asJsonObject
    }

private fun RequestBuilder.withHeaders(headers: Map<String, String>): RequestBuilder =
    tuner { connection -> headers.forEach { (name, value) -> connection.setRequestProperty(name, value) } }

/** Reads a JSON string field, tolerating both absence and an explicit null. */
internal fun JsonObject.stringOrEmpty(field: String): String =
    get(field)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

/** A nested object, or null when the field is absent, null, or not an object. */
internal fun JsonObject.objectOrNull(field: String): JsonObject? =
    get(field)?.takeIf { it.isJsonObject }?.asJsonObject

/**
 * Maps over a JSON array of objects, dropping entries that map to blank.
 *
 * Trackers are inconsistent about empty collections — absent, null, or `[]` — and about which key holds
 * a person's name, so callers pull what they can and this drops the misses.
 */
internal fun JsonObject.mapArray(field: String, select: (JsonObject) -> String): List<String> =
    get(field)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(select)?.takeIf(String::isNotBlank) }
        .orEmpty()

/** Only worth a row when there is something in it; blank values are left out rather than shown empty. */
internal fun MutableList<TaskProperty>.addIfPresent(label: String, value: String?) {
    if (!value.isNullOrBlank()) add(TaskProperty(label, value))
}

/**
 * Milliseconds as `2h 15m`, or `45m`, or `30s`.
 *
 * Deliberately coarse: this sits in a details pane next to a date, not in a timesheet, and a running
 * seconds count would only draw the eye.
 */
internal fun formatDuration(millis: Long): String {
    if (millis <= 0) return ""
    val totalMinutes = millis / 60_000
    if (totalMinutes == 0L) return "${millis / 1000}s"
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}

/**
 * `Basic base64(user:secret)` — how `NewBaseRepositoryImpl` authenticates the platform's own reads, so
 * a server whose issue list already loads will accept our writes on the same credentials.
 */
internal fun basicAuth(username: String, secret: String): String =
    "Basic " + Base64.getEncoder().encodeToString("$username:$secret".toByteArray(StandardCharsets.UTF_8))

/** The configured username and secret, or null when the secret is missing. */
internal fun TaskRepository.credentialsOrNull(): Pair<String, String>? {
    val base = this as? BaseRepository ?: return null
    val secret = base.password?.takeIf { it.isNotBlank() } ?: return null
    return base.username.orEmpty() to secret
}

/** The server root with any trailing slash removed, or null when it isn't set. */
internal fun TaskRepository.baseUrlOrNull(): String? = url?.trimEnd('/')?.takeIf { it.isNotBlank() }
