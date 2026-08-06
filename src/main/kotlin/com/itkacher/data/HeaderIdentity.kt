package com.itkacher.data

import java.util.Locale

internal data class HeaderIdentity(
    val name: String,
    val value: String
)

internal fun String.headerIdentity(): HeaderIdentity {
    val delimiterIndex = indexOf(':')
    if (delimiterIndex < 0) {
        return HeaderIdentity("", trim())
    }

    return HeaderIdentity(
        name = substring(0, delimiterIndex).trim().lowercase(Locale.ROOT),
        value = substring(delimiterIndex + 1).trim()
    )
}
