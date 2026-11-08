package me.ash.reader.infrastructure.rss.provider.greader

/**
 * Normalizes a FreshRSS server URL: trims whitespace, ensures a trailing
 * slash and appends the FreshRSS-specific API path `api/greader.php` unless
 * the URL already points at it.
 *
 * FreshRSS only: other Google Reader compatible servers expose their API at
 * different paths, so they must keep providing the full URL.
 *
 * @link https://github.com/ReadYouApp/ReadYou/issues/1064
 */
fun normalizeFreshRssServerUrl(serverUrl: String): String {
    val trimmedUrl = serverUrl.trim()
    if (trimmedUrl.isEmpty()) return trimmedUrl
    val withTrailingSlash = if (trimmedUrl.endsWith("/")) trimmedUrl else "$trimmedUrl/"
    return if (withTrailingSlash.endsWith("greader.php/")) {
        withTrailingSlash
    } else {
        "${withTrailingSlash}api/greader.php/"
    }
}
