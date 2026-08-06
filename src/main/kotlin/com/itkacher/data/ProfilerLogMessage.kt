package com.itkacher.data

data class ProfilerLogMessage(
    val requestId: String,
    val type: MessageType
) {
    companion object {
        private val supportedTags = setOf("OKPRFL", "KTRPRFL")

        fun parse(tag: String): ProfilerLogMessage? {
            val parts = tag.split('_')
            if (parts.size != 3 || parts[0] !in supportedTags || parts[1].isBlank()) {
                return null
            }

            val type = MessageType.fromString(parts[2])
            return type.takeUnless { it == MessageType.UNKNOWN }
                ?.let { ProfilerLogMessage(parts[1], it) }
        }
    }
}
