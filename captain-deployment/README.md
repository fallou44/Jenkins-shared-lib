# Jenkins Shared Library — CapRover Deployment

> Pipeline CI/CD standardisé pour le déploiement d'applications sur **CapRover**.
> Routing automatique `branche → environnement` — aucune logique dans les projets.

---

## 🔀 Logique de routing

| Branche Git | Environnement | CapRover URL            | Credential                  |
|-------------|---------------|-------------------------|-----------------------------|
| `main`      | **PROD** 🔴   | `CAPTAIN_URL_PROD`      | `caprover-prod-password`    |
| `develop`   | **DEV** 🟡    | `CAPTAIN_URL_DEV`       | `caprover-dev-password`     |
| Autre       | —  Ignoré ⚪  | —                       | —                           |

---

## 🚀 Usage dans les projets

Copier ce fichier à la racine du projet sous le nom **`Jenkinsfile`** :

```groovy
@Library('jenkins-shared-lib@main') _

sharedPipeline(appName: 'mon-api')
```

**C'est tout.** La détection de branche, les credentials, les notifications sont gérés automatiquement.

### Paramètres optionnels

| Paramètre          | Type     | Description                                              |
|--------------------|----------|----------------------------------------------------------|
| `appName`          | `String` | ⚠️ **Requis** — nom de l'app dans CapRover              |
| `notifyEmails`     | `String` | Emails de notif (`;`-séparés). Défaut: `NOTIFY_EMAIL_DEFAULT` |
| `fromEmail`        | `String` | Email expéditeur. Défaut: `FROM_MAIL`                   |
| `dockerImage`      | `String` | Image Docker de l'agent. Défaut: `fadildev/jenkins-node-caprover:1.0` |

---

## ⚙️ Configuration Jenkins requise

### Variables d'environnement globales

> **Manage Jenkins → Configure System → Global properties → Environment variables**

| Variable              | Valeur                                  |
|-----------------------|-----------------------------------------|
| `CAPTAIN_URL_PROD`    | `https://captain.prod.company.com`      |
| `CAPTAIN_URL_DEV`     | `https://captain.dev.company.com`       |
| `NOTIFY_EMAIL_DEFAULT`| `dev@company.com;ops@company.com`       |
| `FROM_MAIL`           | `jenkins@company.com`                   |

### Credentials Jenkins

> **Manage Jenkins → Credentials → Global → Add Credentials** (type: `Secret text`)

| ID                       | Description                       |
|--------------------------|-----------------------------------|
| `caprover-prod-password` | Mot de passe CapRover **PROD**    |
| `caprover-dev-password`  | Mot de passe CapRover **DEV**     |

---

## 📁 Structure de la shared-lib

```
captain-deployment/
├── vars/
│   ├── sharedPipeline.groovy        # Point d'entrée — pipeline complet
│   ├── detectEnvironment.groovy     # Résolution branche → env (dev/prod/skip)
│   └── sendNotification.groovy      # Envoi email HTML post-déploiement
└── Jenkinsfile.template             # Fichier de référence pour les projets
```

---

## 🔍 Comportement par branche

### Push sur `develop`
```
Branch Gate    → ✅ DEV détecté
Pre-flight     → ✅ caprover & git ok
Deploy         → 🚀 caprover deploy → CAPTAIN_URL_DEV
Notification   → 📧 Email [DEV] Deployed: mon-api
```

### Push sur `main`
```
Branch Gate    → ✅ PROD détecté
Pre-flight     → ✅ caprover & git ok
Deploy         → 🚀 caprover deploy → CAPTAIN_URL_PROD
Notification   → 📧 Email [PROD] Deployed: mon-api
```

### Push sur `feature/xxx`
```
Branch Gate    → ⚪ Branch non déployable → Pipeline skippé (NOT_BUILT)
               → Aucun déploiement, aucune notification d'échec
```
