package com.example.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe per-host CookieJar that captures KodBox PHP session cookies
 * (PHPSESSID, CSRF_TOKEN, kodToken, etc.) and supports persisting/hydrating
 * session headers across app restarts.
 */
class KodSessionCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val hostMap = store.getOrPut(url.host) { ConcurrentHashMap() }
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            if (cookie.expiresAt < now || cookie.value == "deleted") {
                hostMap.remove(cookie.name)
            } else {
                hostMap[cookie.name] = cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val hostMap = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        val valid = mutableListOf<Cookie>()
        val iterator = hostMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.expiresAt < now) {
                iterator.remove()
            } else if (entry.value.matches(url)) {
                valid.add(entry.value)
            }
        }
        return valid
    }

    fun exportHeaderForHost(host: String): String {
        val hostMap = store[host] ?: return ""
        return hostMap.values.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun getCsrfToken(host: String): String {
        val hostMap = store[host] ?: return ""
        return hostMap["CSRF_TOKEN"]?.value
            ?: hostMap["csrf_token"]?.value
            ?: ""
    }

    fun hydrateFromHeader(url: HttpUrl, cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        val hostMap = store.getOrPut(url.host) { ConcurrentHashMap() }
        cookieHeader.split(";").forEach { pair ->
            val trimmed = pair.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isNotEmpty()) {
                    val cookie = Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(url.host)
                        .path("/")
                        .build()
                    hostMap[name] = cookie
                }
            }
        }
    }

    fun clearHost(host: String) {
        store.remove(host)
    }

    fun clear() {
        store.clear()
    }
}
