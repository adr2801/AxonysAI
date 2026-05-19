# Axonys AI (Jarvis)

Axonys AI est un projet visant à implémenter une intelligence artificielle proactive et personnalisée sous la forme d'un majordome ("Jarvis"). Il se décompose en un serveur backend Python (pour le moteur cognitif) et une application cliente Android.

## Structure du Projet

- **`backend/`** : Le moteur Python qui fait tourner l'intelligence artificielle (Jarvis), la gestion de la mémoire, et le serveur API.
- **`AxonysAndroidApp/`** : L'application mobile Android permettant à l'utilisateur de discuter avec Jarvis, de recevoir des notifications et de gérer son espace.
- **`docs/`** : Documentation du projet.

## 1. Lancer le Backend (Serveur Python)

Le backend est responsable du traitement des requêtes, de l'historique et des outils de l'IA (méteo, web, mails, etc.).

### Prérequis
- Python 3.9+
- Les clés d'API requises (Gemini, Serper, etc.)

### Installation
1. Allez dans le répertoire du backend :
   ```bash
   cd backend
   ```
2. Créez un environnement virtuel (optionnel mais recommandé) :
   ```bash
   python -m venv venv
   source venv/bin/activate  # Sur Linux/Mac
   venv\Scripts\activate     # Sur Windows
   ```
3. Installez les dépendances :
   ```bash
   pip install -r requirements.txt
   ```
4. Configurez vos variables d'environnement. Créez un fichier `.env` dans le dossier `backend` avec le contenu suivant :
   ```env
   GEMINI_API_KEY=votre_cle_api_gemini
   SERPER_API_KEY=votre_cle_api_serper
   ```

### Lancement
Pour démarrer le serveur API :
```bash
python api_server.py
```
Le serveur sera alors accessible localement sur le port `7860`.

## 2. Compiler et Lancer l'Application Android

Le dossier `AxonysAndroidApp/` contient un projet Gradle standard pour Android.

### Prérequis
- Android Studio ou les outils en ligne de commande Android (SDK)
- JDK 17+

### Lancement via Android Studio
1. Ouvrez **Android Studio**.
2. Cliquez sur **Open** et sélectionnez le dossier `AxonysAndroidApp/`.
3. Attendez la synchronisation Gradle.
4. Cliquez sur **Run** (bouton vert) pour déployer sur un émulateur ou un téléphone connecté.

### Remarques sur l'API
L'application Android doit pouvoir se connecter au backend Python. Si vous lancez le backend sur votre ordinateur, assurez-vous que l'application Android pointe vers l'adresse IP locale de votre ordinateur (ex: `192.168.1.X:7860`), et non `localhost` (qui pointerait vers l'appareil Android lui-même).
