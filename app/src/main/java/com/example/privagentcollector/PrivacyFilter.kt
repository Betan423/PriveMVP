package com.example.privagentcollector

/** Best-effort guardrails; package names and content cannot perfectly identify sensitive screens. */
object PrivacyFilter {
    private val protectedNames = listOf("bank", "banking", "wallet", "authenticator", "password",
        "bitwarden", "1password", "lastpass", "keepass", "vault", "com.google.android.apps.authenticator2",
        "com.azure.authenticator", "com.chb.mbank", "com.ctbcbank", "com.esun", "com.cathay",
        "com.fubon", "com.sinopac", "com.tcb")
    private val secrets = Regex("(?i)(otp|verification|passcode|one.time|驗證|認證|一次性|密碼)|(?<![0-9])[0-9]{4,8}(?![0-9])")
    fun blocked(packageName: String?, ownPackage: String, exclusions: Set<String> = emptySet()): Boolean {
        val name = packageName?.lowercase() ?: return true
        return name == ownPackage || name in exclusions || protectedNames.any { name.contains(it) }
    }
    fun containsSecret(text: String?) = text != null && secrets.containsMatchIn(text)
    fun text(value: CharSequence?): String? = value?.toString()?.let {
        if (containsSecret(it)) "[REDACTED]" else it.take(512)
    }
}
