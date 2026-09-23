# Architecture de FastDrop

## 1. Sécurité Cryptographique

L'architecture de sécurité de FastDrop repose sur un principe hybride garantissant l'identité persistante et le secret de transfert (forward secrecy).

### 1.1 Primitives utilisées

*   **Ed25519** : Assure l'identité persistante des appareils. Chaque appareil génère un couple de clés lors du premier lancement (via `IdentityStore`). L'empreinte (`fingerprint`) est dérivée par `SHA-256(Ed25519 PublicKey)`.
*   **X25519** : Clés éphémères générées à chaque nouvelle session de connexion pour garantir le "forward secrecy" absolu.
*   **HKDF-SHA-256** : Pour la dérivation des clés de session asymétriques directionnelles (`key_A_to_B`, `key_B_to_A`) ainsi que le code SAS de vérification de premier contact.
*   **ChaCha20-Poly1305** : Chiffrement authentifié (AEAD) robuste assurant l'intégrité et la confidentialité des chunks transférés sur le réseau.

### 1.2 Le Transcript Canonique

Pour protéger l'échange éphémère X25519, un "Transcript" déterministe est généré et signé par les identités Ed25519.
Il comprend :
*   Le protocole et sa version (`FASTDROP/1`)
*   La clé publique identitaire Ed25519 de A et de B
*   La clé publique éphémère X25519 de A et de B

Le rôle A vs B est déterminé cryptographiquement (comparaison lexicale des octets des clés éphémères) de façon à éviter toute ambiguïté sur le réseau peer-to-peer.

### 1.3 Modèle de Menace

#### Scénarios protégés
*   **Écoute réseau** : Totalement impossible grâce au secret dérivé (ChaCha20-Poly1305).
*   **Modification des données** : Rejetée instantanément grâce au tag Poly1305.
*   **Usurpation d’un peer déjà trusted** : Bloquée car le pirate ne possède pas la clé privée Ed25519 pour signer le transcript.
*   **Remplacement des clés X25519** : Bloqué par la vérification croisée des signatures Ed25519 couvrant obligatoirement le transcript (et donc, les clés éphémères échangées).
*   **MITM au premier pairing** : Détectable mathématiquement grâce à l'obligation pour les utilisateurs de vérifier oralement ou visuellement le SAS.

#### Limitations (non traitées ou partielles)
*   Compromission locale de la clé privée (si le terminal est infecté par un malware).
*   Attaques DoS (Déni de service) sur les ports d'écoute.
*   Erreur humaine (un utilisateur qui valide le SAS sans le vérifier).
*   Découverte réseau / tracking : Les adresses IP et potentiellement l'identité publique sont transmises en clair lors du handshake initial.
*   Révocation d'un appareil : Non encore synchronisée entre les différentes machines d'un utilisateur.

### 1.4 Les Composants Kotlin

*   **`TransferManager`** : N'a aucune connaissance de la cryptographie (abstraction de flux purs).
*   **`SecureChannel`** : Implémente le handshake et maintient l'état cryptographique par-dessus n'importe quel `Connection`.
*   **`IdentityStore`** : Fournit la persistance asymétrique.
*   **`TrustedPeerStore`** : Mémorise les appareils validés (bypass du SAS).
