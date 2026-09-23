package com.fastdrop.core

/**
 * Représente un type de transport réseau disponible.
 */
enum class TransportType {
    LAN,
    WIFI_DIRECT,
    BLE,
    UNKNOWN
}

/**
 * Représente un appareil distant découvert sur le réseau.
 */
data class Peer(
    val id: String, // Identifiant unique (ex: hash de la clé publique ou UUID)
    val name: String, // Nom de l'appareil ("Laptop de Jean")
    val transportType: TransportType,
    val address: String // Adresse IP, MAC, ou identifiant de transport spécifique
)
