# FastDrop

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
