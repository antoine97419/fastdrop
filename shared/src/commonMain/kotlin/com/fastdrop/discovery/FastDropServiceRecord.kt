package com.fastdrop.discovery

/** Public discovery metadata; authentication still happens in SecureChannel. */
data class FastDropServiceRecord(
    val version: Int,
    val deviceId: String?,
    val name: String,
    val capabilities: Set<String>
) {
    fun txt(): Map<String, String> = mapOf(
        "version" to version.toString(),
        "deviceId" to (deviceId ?: ""),
        "name" to name,
        "capabilities" to capabilities.sorted().joinToString(",")
    )

    companion object {
        fun parse(txt: Map<String, String>, instanceName: String): FastDropServiceRecord? {
            if (txt["version"] != "1") return null
            val id = txt["deviceId"]?.takeIf { it.isNotBlank() }
            if (id != null && !Regex("[0-9A-Fa-f:]{64,95}").matches(id)) return null
            val name = (txt["name"]?.takeIf { it.isNotBlank() } ?: instanceName)
                .trim().trimEnd('?', ' ').takeIf { it.isNotBlank() } ?: return null
            return FastDropServiceRecord(1, id, name,
                txt["capabilities"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                    ?: emptySet())
        }
    }
}
