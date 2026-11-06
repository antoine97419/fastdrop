# FastDrop

FastDrop est l’application de partage de fichiers Android ↔ Windows de la SAE302. Les appareils se découvrent automatiquement quand les deux applications sont ouvertes. L’envoi est bidirectionnel : choisir un appareil, sélectionner un fichier, puis accepter ou refuser l’offre sur le destinataire. Aucun bouton de réception n’est nécessaire.

Chaque connexion utilise une identité Ed25519 persistante, un échange X25519 éphémère et un canal chiffré ChaCha20-Poly1305. Le premier contact affiche un code SAS à comparer sur les deux appareils ; les contacts confirmés deviennent *Trusted*. Les transferts vérifient le SHA-256 du fichier. Les fichiers reçus vont dans `Download/FastDrop` sur Android et `Downloads/FastDrop` sur Windows.

## Lancer et construire

Depuis la racine du dépôt, sous PowerShell :

```powershell
# Publier le bridge Windows une fois sur le PC de rendu
dotnet publish .\shared\bridge\windows\FastDropBridge.csproj -c Release -r win-x64 --self-contained true

# Ouvrir l’application Desktop, sans FASTDROP_BRIDGE_PATH
.\gradlew.bat :desktopApp:run

# Tests et builds
.\gradlew.bat clean :shared:desktopTest :androidApp:assembleDebug :desktopApp:build

# Application Windows autonome (bridge inclus)
.\gradlew.bat :desktopApp:createDistributable
```

L’APK est produit dans `androidApp/build/outputs/apk/debug/`. Le JAR Desktop est dans `desktopApp/build/libs/`. Si `createDistributable` aboutit, l’application Windows autonome est produite dans `desktopApp/build/compose-final/main/app/FastDrop/` avec le bridge inclus. Un MSI peut être créé avec `.\gradlew.bat :desktopApp:packageMsi` sur un poste disposant d’un WiX compatible.

Pour les essais physiques de rendu, vérifier les deux ordres de lancement, Accept/Reject, les transferts successifs et le SHA-256 sur les deux appareils.
