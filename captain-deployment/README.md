# Jenkins Shared Library — CapRover & SonarQube Deployment

Ce module centralise la logique de build, d'analyse statique et de déploiement multi-environnement vers **CapRover** pour l'ensemble des services.
La détection de l'environnement se fait **automatiquement** selon le nom de la branche Git.

---

## 🔀 Logique de Routing automatique

| Branche Git | Environnement | URL CapRover cible | Credential Jenkins |
|-------------|---------------|--------------------|--------------------|
| `main`      | **PROD** 🔴   | `CAPTAIN_URL_PROD` | `caprover-prod-password` |
| `develop`   | **DEV** 🟡    | `CAPTAIN_URL_DEV`  | `caprover-dev-password`  |
| Autre       | — Ignoré ⚪   | Le pipeline s'arrête proprement (NOT_BUILT) |

---

## 🚀 Usage dans les projets

Créez un fichier nommé **`Jenkinsfile`** à la racine de votre application :

```groovy
@Library('jenkins-shared-lib@main') _

sharedPipeline(
    appName: 'nom-de-votre-app',
    
    // Facultatif : Activer SonarQube
    sonarEnabled: true,
    sonarWaitForQualityGate: false // Mettre à true si le webhook SonarQube -> Jenkins est actif
)
```

---

## ⚙️ Paramètres du Pipeline (`sharedPipeline`)

Vous pouvez passer les paramètres suivants au bloc `sharedPipeline(...)` :

| Paramètre | Type | Requis | Description |
|-----------|------|--------|-------------|
| `appName` | `String` | **Oui** | Nom exact de l'application sur CapRover. |
| `notifyEmails` | `String` | Non | Adresses email à notifier (séparées par `;`). Par défaut : valeur globale `NOTIFY_EMAIL_DEFAULT`. |
| `fromEmail` | `String` | Non | Adresse email de l'expéditeur du rapport. Par défaut : valeur globale `FROM_MAIL`. |
| `sonarEnabled` | `Boolean` | Non | Active l'étape d'analyse SonarQube si défini à `true` (défaut : `false`). |
| `sonarServer` | `String` | Non | Nom de la configuration du serveur SonarQube dans Jenkins (défaut : `'SonarQube'`). |
| `sonarWaitForQualityGate` | `Boolean` | Non | Bloque le build jusqu'au retour de la Quality Gate SonarQube (défaut : `false`). |

---

## 🛠️ Configurations Requises dans Jenkins

### 1. Propriétés Globales (Variables d'environnement)
Configurez-les dans **Administrer Jenkins** → **Configurer le système** → **Propriétés globales** → **Variables d'environnement** :

*   `CAPTAIN_URL_PROD` : L'URL HTTPS d'accès à votre instance CapRover de Production (ex: `https://captain.labs.odc.sn.gestionecoleodc.com`).
*   `CAPTAIN_URL_DEV` : L'URL HTTPS d'accès à votre instance CapRover de Développement.
*   `NOTIFY_EMAIL_DEFAULT` : La liste d'adresses email de notification par défaut (séparées par `;` - ex: `dev@company.com`).
*   `FROM_MAIL` : L'adresse email d'expédition de Jenkins (ex: `jenkins@company.com`).

### 2. Identifiants Jenkins (Credentials)
Créez ces credentials dans **Administrer Jenkins** → **Credentials** → **System** → **Global credentials** → **Add Credentials** :

*   `caprover-prod-password` (Type : **Secret text**) : Le mot de passe administrateur de votre instance CapRover **PROD**.
*   `caprover-dev-password` (Type : **Secret text**) : Le mot de passe administrateur de votre instance CapRover **DEV**.

---

## 📧 Configuration des Notifications Email (SMTP)

Le pipeline utilise le plugin **Extended E-mail Notification** (`emailext`) pour envoyer des rapports graphiques HTML. Vous devez configurer le serveur SMTP dans Jenkins :

1. Allez dans **Administrer Jenkins** → **Configurer le système**.
2. Faites défiler jusqu'à la section **Extended E-mail Notification** (*Notification par email étendue*) :
   * **SMTP Server** : `smtp.gmail.com`
   * **SMTP Port** : `587`
   * Cliquez sur **Avancé...** et cochez **Use SMTP Authentication** :
     * **Nom d'utilisateur** : Votre adresse Gmail (ex: `seck22331@gmail.com`).
     * **Mot de passe** : Un *App Password* (Mot de passe d'application) généré sur votre compte Google (ne mettez jamais votre mot de passe Gmail standard).
   * Cochez la case **Use TLS**.
3. Assurez-vous que la même configuration est renseignée dans la section standard **Notification par e-mail** en bas de page pour que les tests d'envoi fonctionnent.

---

## 🔍 Intégration SonarQube

Pour utiliser la fonctionnalité SonarQube :

1. **Plugin Jenkins** : Installez le plugin **SonarQube Scanner for Jenkins** sur votre serveur Jenkins.
2. **Global Tool Configuration** : Ajoutez une installation de **SonarQube Scanner** nommée de façon standard.
3. **Configure System** : Dans Jenkins, associez votre serveur SonarQube en lui donnant un nom (utilisé pour `sonarServer`, ex: `SonarQube`) et renseignez l'URL du serveur et le token d'authentification.
4. **Fichier `sonar-project.properties`** : Ajoutez un fichier de configuration à la racine de votre application pour guider l'analyseur. Exemple :
   ```properties
   sonar.projectKey=my-backend-project
   sonar.projectName=My Backend Project
   sonar.sources=src
   sonar.exclusions=**/node_modules/**,**/dist/**,**/uploads/**
   sonar.javascript.lcov.reportPaths=coverage/lcov.info
   ```

---

## ⚙️ Fonctionnement interne & Optimisations

* **Déploiement Hybride intelligent** : Le pipeline vérifie la présence du CLI `caprover` ou de `npm` sur l'agent d'exécution. S'ils sont absents, il effectue automatiquement et sans échec un repli sur l'API HTTP `curl`.
* **Mode Asynchrone (Detached)** : La requête d'upload vers l'API CapRover utilise le paramètre `?detached=1`. Cela permet de déclencher le build sur le serveur et de libérer immédiatement la connexion, éliminant définitivement les erreurs **504 Gateway Time-out**.
* **Optimisation de la taille du Package (tar)** : Le pipeline exclut automatiquement les dossiers inutiles ou dynamiques de l'archive tar :
  - `uploads/` (évite de réécrire les fichiers téléversés par les utilisateurs et préserve le cache Docker).
  - `node_modules/`, `dist/`, `build/`, `.git/`, `coverage/`, `*.sql` (backups de base de données locaux), `*.zip`.
