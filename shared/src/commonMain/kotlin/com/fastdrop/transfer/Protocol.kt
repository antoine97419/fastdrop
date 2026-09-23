package com.fastdrop.transfer

import kotlinx.serialization.Serializable

/**
 * Messages de contrôle du protocole de transfert.
 * Ces messages sont sérialisés en JSON et encapsulés dans des trames.
 */
@Serializable
sealed class ControlMessage {
    @Serializable
    data class FileOffer(val id: String, val name: String, val size: Long) : ControlMessage()
    
    @Serializable
    data class FileAccept(val id: String) : ControlMessage()
    
    @Serializable
    data class FileReject(val id: String) : ControlMessage()
    
    @Serializable
    data class Cancel(val id: String, val reason: String) : ControlMessage()
    
    @Serializable
    data class Complete(val id: String, val hash: String) : ControlMessage()
    
    @Serializable
    data class Success(val id: String) : ControlMessage()
    
    @Serializable
    data class HashMismatch(val id: String) : ControlMessage()
    
    @Serializable
    data class Error(val id: String, val message: String) : ControlMessage()
}

/**
 * Types de trames pour le multiplexage binaire.
 */
object FrameType {
    const val CONTROL_MESSAGE: Byte = 0x01
    const val CHUNK: Byte = 0x02
}
