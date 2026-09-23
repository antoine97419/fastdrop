package com.fastdrop.transport

import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Implémentation basique et abstraite du transport sur réseau local (LAN).
 * Utilise Ktor Sockets pour les connexions TCP.
 * La découverte (mDNS/UDP) devra être implémentée spécifiquement ou injectée.
 */
abstract class AbstractLanTransport(private val port: Int = 8080) : Transport {

    private val selectorManager = SelectorManager(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    // TODO: La découverte LAN dépend de mDNS (NsdManager sur Android, JmDNS sur JVM).
    // Cette méthode abstraite forcera l'implémentation native ou via une lib commune.
    abstract override suspend fun discover(): Flow<List<Peer>>

    override suspend fun connect(peer: Peer): Connection {
        require(peer.transportType == TransportType.LAN) { "Invalid transport type" }
        
        return withContext(Dispatchers.IO) {
            val socket = aSocket(selectorManager).tcp().connect(peer.address, port)
            KtorConnection(peer, socket)
        }
    }

    override suspend fun startHosting(): Flow<Connection> = flow {
        serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", port)
        
        while (true) {
            val socket = serverSocket?.accept() ?: break
            // Pour l'instant, on crée un Peer générique pour les connexions entrantes
            val remoteAddress = socket.remoteAddress.toString()
            val peer = Peer(id = remoteAddress, name = "Unknown", transportType = TransportType.LAN, address = remoteAddress)
            emit(KtorConnection(peer, socket))
        }
    }

    override suspend fun stop() {
        serverSocket?.close()
        serverSocket = null
        selectorManager.close()
    }
}

/**
 * Implémentation de l'interface Connection utilisant les sockets Ktor.
 */
class KtorConnection(
    override val peer: Peer,
    private val socket: Socket
) : Connection {
    
    private val readChannel = socket.openReadChannel()
    private val writeChannel = socket.openWriteChannel(autoFlush = true)

    override suspend fun read(buffer: ByteArray): Int {
        return readChannel.readAvailable(buffer, 0, buffer.size)
    }

    override suspend fun write(data: ByteArray, offset: Int, length: Int) {
        writeChannel.writeFully(data, offset, length)
    }

    override suspend fun close() {
        socket.close()
    }
}
