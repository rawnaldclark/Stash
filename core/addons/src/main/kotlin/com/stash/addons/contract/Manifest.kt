// Vendored from stash-addon-spec types/kotlin at 6de2370 (Stash Add-ons v1; these files last changed there in 43a5acc). Edit the spec, then copy it here again.
package com.stash.addons.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** Resources a v1 host knows (SPEC.md 2.1). A manifest may list others: hosts ignore them. */
@Serializable
public enum class Resource(public val wire: String) {
    @SerialName("search") SEARCH("search"),
    @SerialName("resolve") RESOLVE("resolve"),
    @SerialName("stream") STREAM("stream"),
    @SerialName("download") DOWNLOAD("download"),
    @SerialName("lyrics") LYRICS("lyrics"),
    @SerialName("album") ALBUM("album"),
    @SerialName("artist") ARTIST("artist"),
    @SerialName("catalog") CATALOG("catalog"),
    @SerialName("quota") QUOTA("quota"),
    @SerialName("account") ACCOUNT("account");

    public companion object {
        public fun fromWire(value: String): Resource? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
public enum class AddonKind {
    @SerialName("server") SERVER,
    @SerialName("phone") PHONE,
    @SerialName("frame") FRAME,
}

/** `GET /manifest.json`, or `manifest.json` in a phone package (SPEC.md 2). */
@Serializable
public data class Manifest(
    /** Contract major; 1 here. v0 and Eclipse-family manifests go through the adapters (SPEC.md 10) first. */
    val stash: Int,
    val id: String,
    val name: String,
    val version: String,
    val kind: AddonKind,
    val description: String? = null,
    val icon: String? = null,
    /** Raw values: unknown ones must be ignored, not rejected. Use [knownResources]. */
    val resources: List<String>,
    val stream: StreamCapabilities? = null,
    val catalogs: List<CatalogInfo>? = null,
    val settings: List<Setting>? = null,
    val auth: Auth? = null,
    val frame: String? = null,
    val phone: PhoneSpec? = null,
) {
    /** The resources this host version knows, in manifest order. */
    val knownResources: List<Resource> get() = resources.mapNotNull(Resource::fromWire)

    public fun offers(r: Resource): Boolean = r.wire in resources
}

@Serializable
public data class StreamCapabilities(
    /** The media host sends Access-Control-Allow-Origin. */
    val cors: Boolean,
    /** Deliveries it can answer (default `["none"]`); raw, unknown values ignored. Use [knownManifests]. */
    val manifests: List<String>? = null,
    /** Tiers it can answer; raw, unknown values ignored. Use [knownTiers]. */
    val tiers: List<String>? = null,
    /** Some answers need request headers. */
    val headers: Boolean? = null,
) {
    val knownManifests: List<Delivery> get() = (manifests ?: listOf("none")).mapNotNull(Delivery::fromWire)
    val knownTiers: List<Tier> get() = tiers.orEmpty().mapNotNull(Tier::fromWire)
}

@Serializable
public data class CatalogInfo(
    val id: String,
    val title: String,
    /** Only "album" in v1. */
    val kind: String,
    val genres: Boolean? = null,
)

@Serializable
public enum class SettingType {
    @SerialName("select") SELECT,
    @SerialName("toggle") TOGGLE,
    @SerialName("text") TEXT,
    @SerialName("number") NUMBER,
}

/** A setting the host draws and sends back as a query parameter named [key] (SPEC.md 2.2). */
@Serializable
public data class Setting(
    val key: String,
    val type: SettingType,
    val label: String,
    val help: String? = null,
    /** A string, boolean or number, by [type]. */
    val default: JsonPrimitive? = null,
    val options: List<SettingOption>? = null,
    val maxLength: Int? = null,
    val placeholder: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val perNetwork: Boolean? = null,
)

@Serializable
public data class SettingOption(val value: String, val label: String)

@Serializable
public enum class TokenPlacement {
    /** `https://host/<token>/manifest.json`. */
    @SerialName("path") PATH,
    /** `X-Access-Token` on every route but the manifest. */
    @SerialName("header") HEADER,
}

@Serializable
public data class Auth(val token: TokenPlacement) {
    public companion object {
        public const val HEADER_NAME: String = "X-Access-Token"
    }
}
