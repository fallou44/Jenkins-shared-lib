# Jenkins Shared Library — CapRover & SonarQube Deployment

Ce module centralise la logique de build, de test, d'analyse statique et de déploiement multi-environnement vers **CapRover** pour l'ensemble des services.
La détection de l'environnement et du type de projet se fait **automatiquement**.

---

## 🔀 Cycle de vie du Pipeline

```
Branch Gate
   ↓
Detect Project (Détection auto: NestJS, Next.js, Vite/React, Node)
   ↓
Pre-flight Check
   ↓
Install Dependencies (Détection lockfile & outil, ignoré sans échec si npm absent)
   ↓
Run Tests (Détection script 'test', ignoré sans échec si npm absent)
   ↓
SonarQube Analysis (Optionnel - statique)
   ↓
Package (Compression tar.gz optimisée)
   ↓
Deploy (Déploiement Hybride : CLI CapRover avec repli automatique sur API curl)
```

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
    sonarWaitForQualityGate: false
)
```

---

## 🔍 Détection de Frameworks & Tests automatiques

Le pipeline examine la racine de l'espace de travail pour détecter le type d'application :
*   `nest-cli.json` → NestJS
*   `next.config.js` / `.mjs` / `.ts` → Next.js
*   `vite.config.js` / `.ts` → Vite / React
*   `package.json` seul → Node.js générique

### Gestion Robuste des Outils
*   **Installation des dépendances** : S'adapte au lockfile présent (`yarn.lock` → `yarn install`, `pnpm-lock.yaml` → `pnpm install`, `package-lock.json` → `npm ci`, absent → `npm install`).
*   **Tests unitaires** : Détecte si la section `scripts` du `package.json` contient une commande `test`. Si elle est présente, elle l'exécute automatiquement.
*   **Sécurité** : Si les outils Node (`npm`, `yarn`, `pnpm`) ne sont pas installés sur l'agent Jenkins d'exécution, le pipeline enregistre un avertissement et **passe les étapes d'installation et de tests proprement** sans planter le build.

---

## ⚙️ Paramètres du Pipeline (`sharedPipeline`)

| Paramètre | Type | Requis | Description |
|-----------|------|--------|-------------|
| `appName` | `String` | **Oui** | Nom exact de l'application sur CapRover. |
| `notifyEmails` | `String` | Non | Adresses email à notifier (séparées par `;`). Par défaut : valeur globale `NOTIFY_EMAIL_DEFAULT`. |
| `fromEmail` | `String` | Non | Adresse email de l'expéditeur du rapport. Par défaut : valeur globale `FROM_MAIL`. |
| `sonarEnabled` | `Boolean` | Non | Active l'étape d'analyse SonarQube si défini à `true` (défaut : `false`). |
| `sonarServer` | `String` | Non | Nom de la configuration du serveur SonarQube dans Jenkins (défaut : `'SonarQube'`). |
| `sonarWaitForQualityGate` | `Boolean` | Non | Bloque le build jusqu'au retour de la Quality Gate SonarQube (défaut : `false`). |

---

## ⚙️ Configurations Requises dans Jenkins

### 1. Propriétés Globales (Variables d'environnement)
Configurez-les dans **Configurer le système** → **Variables d'environnement** :
*   `CAPTAIN_URL_PROD` : L'URL HTTPS d'accès à Production (ex: `https://captain.labs.odc.sn.gestionecoleodc.com`).
*   `CAPTAIN_URL_DEV` : L'URL HTTPS d'accès à Développement.
*   `NOTIFY_EMAIL_DEFAULT` : La liste d'emails de notification par défaut.
*   `FROM_MAIL` : L'adresse email d'expédition de Jenkins.

### 2. Identifiants Jenkins (Credentials)
Configurez ces credentials de type **Secret text** :
*   `caprover-prod-password` : Mot de passe CapRover **PROD**.
*   `caprover-dev-password` : Mot de passe CapRover **DEV**.

### 3. Notifications Email (SMTP)
Dans **Extended E-mail Notification** :
*   **SMTP Server** : `smtp.gmail.com`
*   **SMTP Port** : `587`
*   **Credentials** : Votre adresse Gmail et un **App Password** Google.
*   Cochez **Use TLS**.

### 4. SonarQube
*   Installez le plugin **SonarQube Scanner for Jenkins** dans Jenkins.
*   Associez votre serveur SonarQube sous le nom de configuration `SonarQube`.
