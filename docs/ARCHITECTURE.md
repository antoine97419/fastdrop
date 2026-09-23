# FastDrop - Architecture & Conception

## 1. Objectifs
Application de transfert de fichiers multiplateforme (MVP: Android ↔ Windows) fonctionnant sans infrastructure réseau préalable (hors-ligne complet via Wi-Fi Direct) et sur LAN existant. KMP (Kotlin Multiplatform) est utilisé pour maximiser le partage de code.

## 2. Faisabilité & Défis Techniques (Android ↔ Windows)
* **KMP + Compose Multiplatform** : Idéal pour partager UI et logique. Android fonctionne via Kotlin/JVM et Windows via Compose Desktop (Kotlin/JVM également).
* **Défi Majeur - Wi-Fi Direct sur Windows** : Autant Android expose des APIs claires (`WifiP2pManager`), autant sur Windows en JVM, l'accès à Wi-Fi Direct nécessite d'interagir avec WinRT (`Windows.Devices.WiFiDirect`). Il faudra un adaptateur natif (ex: JNA/JNI ou appel à un processus C#/C++). KMP encapsulera cela via le mot-clé `expect`/`actual` ou des interfaces injectées.
* **Découverte Hors-réseau** : Sans LAN, il faut un réseau. Wi-Fi Direct permet la découverte, mais BLE peut aussi servir pour échanger des métadonnées de connexion et négocier qui crée le hotspot Wi-Fi (Group Owner).

## 3. Architecture Logicielle

L'architecture suit une approche en couches modulaires et agnostique du transport physique :

```text
+-----------------------------------------------------------+
|                      UI (Compose)                         |
+-----------------------------------------------------------+
|                   Application Logic                       |
+-----------------------------------------------------------+
| PeerManager / Discovery |  TransferManager / Protocol     |
+-----------------------------------------------------------+
|                  ConnectionManager                        |
+-----------------------------------------------------------+
|                    SecureChannel                          |
+-----------------------------------------------------------+
|                  TransportManager                         |
+-------------------+-------------------+-------------------+
|    LanTransport   | WifiP2pTransport  |   BleTransport    |
|   (Ktor Sockets)  | (Platform APIs)   | (Platform APIs)   |
+-------------------+-------------------+-------------------+
```

## 4. Arborescence du projet (KMP)

```text
fastdrop/
├── shared/
│   ├── build.gradle.kts
│   ├── src/
│   │   ├── commonMain/kotlin/com/fastdrop/
│   │   │   ├── core/           # Data classes: Peer, DeviceInfo
│   │   │   ├── discovery/      # DiscoveryManager, DiscoveryTransport
│   │   │   ├── transport/      # Transport, Connection, LanTransport
│   │   │   ├── security/       # SecureChannel, Crypto, Handshake Protocol
│   │   │   └── transfer/       # FileTransferProtocol, Chunking
│   │   ├── androidMain/kotlin/com/fastdrop/platform/
│   │   │   └── WifiP2pTransportImpl, NsdDiscoveryImpl
│   │   └── desktopMain/kotlin/com/fastdrop/platform/
│   │       └── WindowsWifiDirectTransportImpl, JmDnsDiscoveryImpl
├── androidApp/
│   └── (Compose UI Android)
└── desktopApp/
    └── (Compose UI Windows)
```

## 5. Composants & Interfaces Principales

### `Transport` & `Connection`
```kotlin
interface Connection {
    suspend fun read(buffer: ByteArray): Int
    suspend fun write(data: ByteArray)
    suspend fun close()
}

interface Transport {
    val transportType: TransportType // LAN, WIFI_DIRECT
    suspend fun discover(): Flow<Peer>
    suspend fun connect(peer: Peer): Connection
    suspend fun startHosting(): Connection // Pour écouter les connexions entrantes
}
```

### `SecureChannel`
S'insère entre la logique applicative et le `Transport`. Ne se soucie pas de *comment* on transmet, uniquement de chiffrer les octets (AEAD).

## 6. Protocole de Transfert (Applicatif)

Le protocole est versionné et conçu pour être indépendant du transport physique. Il utilise un format de trame binaire (Framing) pour multiplexer efficacement des messages de contrôle structurés (JSON) et des données brutes (Chunks).

**Format d'une trame :**
*   `Type` (1 octet) : Type du message (OFFER, ACCEPT, CHUNK, etc.)
*   `Length` (4 octets - Entier Big-Endian) : Taille de la charge utile (Payload)
*   `Payload` (Taille variable) : Contenu du message

**Sécurité du Framing :**
*   Taille maximale stricte : 64 KiB pour une trame de contrôle, et [Taille Max Chunk] pour une trame de données.
*   Rejet immédiat en cas de longueur négative, invalide ou type de trame inconnu.
*   Protection contre la fragmentation TCP : le protocole reconstruit méticuleusement les trames (via une lecture stricte des octets requis).

**Messages :**
1.  `FILE_OFFER(id, name, size)` -> Proposition d'envoi. Le SHA-256 complet n'est **pas** inclus pour permettre le hachage à la volée de très gros fichiers (plusieurs Go).
2.  `FILE_ACCEPT(id)` -> Le receveur accepte le transfert.
3.  `FILE_REJECT(id)` -> Le receveur refuse le transfert.
4.  `CHUNK(offset, data)` -> Morceau de fichier.
    *   Payload = `[8 octets Offset] + [Données brutes]`.
5.  `CANCEL(id, reason)` -> Annulation explicite du transfert en cours.
6.  `COMPLETE(id, hash)` -> L'émetteur a fini d'envoyer (EOF) et transmet le hash SHA-256 final calculé à la volée.
7.  `SUCCESS(id)` -> Le receveur valide le hash final.
8.  `HASH_MISMATCH(id)` -> Erreur d'intégrité (hash final différent).
9.  `ERROR(id, message)` -> Autre erreur réseau ou I/O.

**Réception Sûre :**
Les fichiers sont reçus avec un suffixe temporaire (`.fastdrop-part`). Le renommage n'a lieu qu'après la validation du hash final (message `COMPLETE`). Les noms de fichiers sont purgés de toute tentative de Path Traversal (ex: rejet de `../`).

## 7. Stratégie de Sécurité

Le réseau physique (LAN ou Wi-Fi P2P) est considéré **hostile**.
*   **Handshake** : Échange de clés **X25519** pour générer un secret partagé. *(Note: Ed25519 sera réservé à l'identité/signature persistante des appareils).*
*   **Dérivation de clé** : Le secret partagé est dérivé avec **HKDF-SHA-256** pour produire les clés symétriques.
*   **Authentification** : Short Authentication String (SAS). Un PIN est généré à partir du hash du secret. Les deux utilisateurs doivent valider (mitige le Man-in-the-Middle).
*   **Chiffrement de Session** : Le trafic est chiffré par un algorithme AEAD, spécifiquement **ChaCha20-Poly1305**.
*   **Confiance (Trust)** : Après la première validation SAS, l'identité (Ed25519) du pair est stockée localement.
*   **Bibliothèques** : Utilisation de `cryptography-kotlin` pour fournir une abstraction KMP propre des primitives.

## 8. Bibliothèques Pertinentes
* `kotlinx-coroutines` (Asynchronisme et Flows)
* `ktor-network` (Sockets TCP/UDP purs en Multiplatform pour le LAN)
* `kotlinx-serialization` (Messages de protocole)
* `okio` (Manipulation efficace de flux et fichiers en multiplateforme, I/O asynchrone)
* `korlibs-crypto` ou `krypt` (Primitives cryptographiques)

## 9. Risques Techniques
1. **Wi-Fi Direct sur Windows** : L'API WinRT via JVM est ardue. *Mitigation*: Restreindre d'abord au LAN pour le MVP, puis créer un mini-exécutable ou module JNA dédié à l'appel natif.
2. **Fragmentation Android** : Le comportement de `WifiP2pManager` varie selon les constructeurs. *Mitigation*: Isoler ce code et prévoir des fallbacks propres.
3. **Multiples interfaces réseau** : Détecter la bonne IP sur un LAN. *Mitigation*: mDNS/DNS-SD (via multicast) aide à résoudre l'IP locale exacte du pair.

## 10. MVP & Ordre de Développement

*   **Phase 1** : Squelette KMP, interfaces métiers communes (`Transport`, `Connection`, `Peer`).
*   **Phase 2** : `LanTransport` basé sur TCP (`ktor-network`) avec un mock discovery (IP tapée manuellement). Envoi d'un fichier en clair.
*   **Phase 3** : Ajout du `SecureChannel` (ECDH + Chiffrement).
*   **Phase 4** : Découverte automatique LAN (Multicast / mDNS).
*   **Phase 5** : Implémentation `WifiP2pTransport` (Android ↔ Android d'abord, car plus simple à tester).
*   **Phase 6** : Adaptateur Windows Wi-Fi Direct.
*   **Phase 7** : `ConnectionManager` avec Fallback intelligent (LAN > P2P).
