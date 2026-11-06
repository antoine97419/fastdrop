# FastDrop

## Application Windows

Lancer la fenêtre avec `./gradlew.bat :desktopApp:run` sous PowerShell. Le CLI de diagnostic reste accessible avec `./gradlew.bat :shared:runFastDropDesktop --args="--dev"`.

À l'ouverture, le runtime charge l'identité persistante, publie et parcourt `_fastdrop._tcp.local.`, démarre le bridge Wi-Fi Direct puis le listener TCP. À la fermeture, il ferme la session, le listener, la discovery et le bridge. Les fichiers reçus vont dans `Downloads/FastDrop`.

Le TXT mDNS contient `version=1`, `deviceId` (SHA-256 public de la clé Ed25519, format hexadécimal séparé par `:`), `name` et `capabilities=receive,send`. Le port est celui du service DNS-SD. L'identité mDNS sert à la présentation et à retrouver un peer connu ; SecureChannel vérifie toujours la clé pendant la connexion.

Sur le même LAN, Android affiche uniquement les services FastDrop résolus par NSD. Un SSID Wi-Fi Direct seul ne devient pas une carte FastDrop. La découverte hors LAN commun et le choix automatique du transport restent à faire.

Sur un LAN commun, chaque application écoute en permanence sur le port FastDrop. L'émetteur choisit d'abord son fichier, puis ouvre un nouveau TCP et un nouveau SecureChannel. La négociation de session précède `FILE_OFFER` : `HELLO(version=1)` dans les deux sens, `SEND_INTENT`, puis `READY` ou `BUSY`. Pour Windows → Android, Android initie aussi une session préparée avec `RECEIVE_READY` ; Windows répond ultérieurement par `SEND_INTENT`, puis Android par `READY` ou `BUSY`. `CANCEL(reason)` couvre les versions incompatibles et les intents invalides. Ces messages JSON typés sont transportés par une trame de session de quatre octets de longueur, distincte des trames de fichier inchangées. Une session est fermée après un transfert.

Le TXT mDNS inclut `sessionVersion=1`. Une annonce ancienne sans ce champ reste visible, mais le flux automatique refuse de lui envoyer une négociation qu'elle ne comprend pas. L'écran Android ne demande plus `Receive files` : un listener LAN reçoit l'offre et affiche Accept / Reject. Une seule session de transfert est active par appareil ; une seconde demande reçoit `BUSY`.

Le Wi-Fi Direct Legacy GO conserve son sens de connexion Android vers Windows. Le flux automatique Windows vers Android utilise le LAN commun ; sans LAN, le fallback Wi-Fi Direct automatique nécessite encore un canal de contrôle ou un mécanisme d'intention supplémentaire. Le CLI de diagnostic et les anciens workflows de transfert restent séparés.

FastDrop est un projet étudiant (SAE BUT Réseaux & Télécommunications) visant à créer une application de transfert de fichiers peer-to-peer (P2P) multiplateforme.
L'objectif est d'offrir une expérience similaire à AirDrop, fonctionnant entre Android et Windows, même en l'absence totale d'infrastructure réseau (pas de routeur, pas de connexion Internet).

## Caractéristiques Principales
- **Multiplateforme** : Kotlin Multiplatform (Android & JVM Desktop pour Windows).
- **Découverte Automatique** : Utilisation de mDNS (sur LAN) et Wi-Fi Direct.
- **Connectivité Résiliente** : Privilégie le LAN si disponible, sinon bascule sur une connexion Wi-Fi Direct autonome.
- **Sécurisé** : Chiffrement de bout en bout (E2E) basé sur des primitives cryptographiques robustes et validation par code (SAS) contre les attaques de l'homme du milieu (MitM).

## Documentation
- [Architecture & Conception technique](docs/ARCHITECTURE.md)

## Démarrage rapide (Développement)
*(À compléter au fur et à mesure du développement de la structure KMP)*

## Lancement des tests
*(À compléter)*
