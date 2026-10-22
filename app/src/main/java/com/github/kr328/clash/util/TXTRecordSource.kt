package com.github.kr328.clash.util

/**
 * Where a member card's TXT record can be read from.
 *
 * The record is mirrored across two independent DNS providers so that losing
 * one does not take member cards down. Callers try the URLs in order and use
 * the first that yields a usable record; only when every provider fails is the
 * lookup a genuine failure.
 *
 * Hostinger comes first: it is written on every change, propagates in under a
 * second, and its records are not tied to a provider-specific record id. Dynu
 * remains as the fallback for cards that predate the mirror.
 */
object TXTRecordSource {

    private const val DOH_RESOLVER = "https://dns.alidns.com/resolve"

    /**
     * Hostnames holding this node code's TXT record, in the order to try them.
     *
     * Hostinger stores names in lowercase (its API rejects uppercase), while
     * Dynu keeps the node code as-is. DNS lookups are case-insensitive either
     * way, so both resolve regardless of how the user typed the code.
     */
    fun hostnamesFor(nodeCode: String): List<String> = listOf(
        "${nodeCode.lowercase()}.stservice.online",
        "$nodeCode.stservice${nodeCode.takeLast(2)}.ddnsfree.com"
    )

    /**
     * DoH query URLs for this node code, in the order to try them.
     */
    fun urlsFor(nodeCode: String): List<String> =
        hostnamesFor(nodeCode).map { "$DOH_RESOLVER?name=$it&type=TXT" }
}
