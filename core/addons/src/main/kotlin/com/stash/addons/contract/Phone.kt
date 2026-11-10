// Vendored from stash-addon-spec types/kotlin at 8ca8e83 (Stash Add-ons v1, draft 2). Edit the spec, then copy it here again.
package com.stash.addons.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `phone` block of a phone add-on's manifest (SPEC.md 8.2). */
@Serializable
public data class PhoneSpec(
    val bundle: Bundle,
    /** Hostnames, or `*.` + hostname, the add-on may reach. Every fetch and redirect hop is checked. */
    val domains: List<String>,
    val signIn: SignIn? = null,
) {
    /** True when [host] (lower case) is allowed by [domains]. `*.x` covers subdomains of x, not x itself. */
    public fun allows(host: String): Boolean = domains.any { d ->
        if (d.startsWith("*.")) host.endsWith(d.substring(1)) && host.length > d.length - 1 else host == d
    }
}

@Serializable
public data class Bundle(val path: String, val sha256: String)

/** What the host's sign-in screen shows and waits for (SPEC.md 8.2, 8.5). */
@Serializable
public data class SignIn(
    val title: String,
    val url: String,
    /** The cookie domain. */
    val domain: String,
    /** The cookies that mean "signed in". */
    val cookieNames: List<String>,
    val accounts: AccountCount,
)

@Serializable
public enum class AccountCount {
    @SerialName("one") ONE,
    @SerialName("many") MANY,
}

/** What a phone add-on's `signIn` answers (SPEC.md 8.5). */
@Serializable
public data class Account(val id: String, val name: String, val avatar: String? = null)

/** A link to a phone add-on package (SPEC.md 8.1). [signer]: SHA-256 of the key's SubjectPublicKeyInfo, hex. */
@Serializable
public data class PackageRef(val url: String, val sha256: String, val signer: String)

/** The three entries of a package ZIP (SPEC.md 8.1). */
public object PackageLayout {
    public const val MANIFEST: String = "manifest.json"
    public const val SIGNATURE: String = "manifest.sig"
    public const val DEFAULT_BUNDLE: String = "main.js"
    /** ECDSA P-256 over SHA-256, DER-encoded, as `java.security.Signature.getInstance(SIGNATURE_ALGORITHM)` reads it. */
    public const val SIGNATURE_ALGORITHM: String = "SHA256withECDSA"
    public const val MAX_ZIP_BYTES: Long = 5L * 1024 * 1024
    public const val MAX_BUNDLE_BYTES: Long = 4L * 1024 * 1024
    public const val MAX_MANIFEST_BYTES: Long = 64L * 1024
}
