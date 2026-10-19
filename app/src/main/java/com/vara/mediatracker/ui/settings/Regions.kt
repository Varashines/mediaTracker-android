package com.vara.mediatracker.ui.settings

/**
 * Curated provider regions offered in Settings — the common streaming markets.
 *
 * In its own file because the settings screen that used to declare it is gone; the M3E settings is
 * the only reader now.
 */
internal val ProviderRegions = listOf(
    "US", "GB", "IN", "CA", "AU", "DE", "FR", "ES", "IT", "NL", "SE", "NO", "DK",
    "FI", "PL", "PT", "IE", "CH", "AT", "BE", "JP", "KR", "CN", "HK", "TW", "SG",
    "PH", "ID", "TH", "VN", "MY", "BR", "MX", "AR", "TR", "AE", "SA", "ZA", "NZ"
)

internal fun regionDisplayName(code: String): String =
    java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH)
