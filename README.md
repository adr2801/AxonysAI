# 🧠 Axonys AI (Jarvis)

**Axonys AI** est un système d'agent autonome et d'assistant personnel proactif conçu sous la forme d'un majordome intelligent ("Jarvis"). Il associe un **serveur cognitif Python** outillé (MCP, Supabase pgvector, Google GenAI) à une **application native Android** réactive en Jetpack Compose dotée d'intelligence embarquée (MediaPipe et Perceptron MLP).

---

## 📑 Sommaire
- [Structure du Projet](#-structure-du-projet)
- [Fonctionnalités principales](#-fonctionnalités-principales)
- [1. Configuration et lancement du Backend (Python)](#1-configuration-et-lancement-du-backend-python)
- [2. Configuration et compilation de l'Application Android](#2-configuration-et-compilation-de-lapplication-android)
- [Endpoints API principaux](#-endpoints-api-principaux)
- [Base de données et Mémoire](#-base-de-données-et-mémoire)

---

## 🏗 Structure du Projet

```
AxonysAI/
├── backend/                       # Moteur cognitif Python & API REST/SSE
│   ├── api_server.py              # Serveur FastAPI (Endpoints Chat, SSE, Mémoire, Tâches, Modes, Bridge)
│   ├── jarvis_engine.py           # Cœur de l'agent (Gemini/Gemma, Sessions, Tool Calling, Contexte)
│   ├── memory_manager.py          # Gestionnaire de mémoire vectorielle (Supabase + pgvector 768d)
│   ├── mcp_server.py              # Serveur MCP (Google Workspace, Serper Search, Python Sandbox, etc.)
│   └── requirements.txt           # Dépendances Python
│
├── AxonysAndroidApp/              # Application mobile Android native (non-disponible en open-source si le projet vous intéresse contactez-moi)
│   ├── app/src/main/java/com/axonys/ai/
│   │   ├── MainActivity.kt        # Point d'entrée, cycle de vie et autorisations
│   │   ├── MainViewModel.kt       # Gestion de l'état UI, flux SSE, audio et modes
│   │   ├── AxonysApiClient.kt     # Client HTTP/SSE vers le backend
│   │   ├── AxonysIsland.kt        # Dynamic Island & Overlay flottant par-dessus les applications
│   │   ├── AxonysMlpInference.kt  # Moteur d'inférence de réseau de neurones (MLP natif)
│   │   ├── AxonysLocalIntelligence.kt # IA locale embarquée (Gemma 2B via MediaPipe)
│   │   ├── AnticipationEngine.kt  # Moteur d'anticipation et Workers d'arrière-plan
│   │   └── ui/components/         # Composants d'interface Compose (JarvisOrb, Wave, etc.)
│   └── app/build.gradle.kts       # Configuration de build Gradle (Kotlin, Compose BOM, Coil, etc.)
│
├── index.html / stylesheet.css    # Site vitrine de présentation du projet
├── privacy.html                   # Politique de confidentialité (Conformité Google Workspace OAuth)
└── permissive_qwen.jinja          # Template de prompt personnalisé
```

---

## ✨ Fonctionnalités principales

### 🤖 Cœur Cognitif (Backend)
- **Modèles de langage de pointe** : Intégration du SDK Google GenAI (`gemma-4-31b-it`, `gemini-2.5-flash`, etc.).
- **Protocole MCP (Model Context Protocol)** :
  - **Google Workspace** : Lecture/envoi d'e-mails (Gmail), gestion d'agenda (Google Calendar) et recherche documentaire (Google Drive).
  - **Recherche Web** : Recherche temps réel via Serper API (`search_eye`, `search_deep_eye`, `listen_web`).
  - **Sandbox Python** : Exécution de code à la volée avec génération et renvoi de graphiques (`matplotlib`).
  - **Notes Bridge** : Système de remontée automatique d'anomalies et d'idées techniques à destination de l'équipe de développement.
- **Mémoire sémantique & vectorielle** : Stockage et recherche par similarité vectorielle (`pgvector`) sur Supabase pour la mémorisation long-terme des préférences et faits de l'utilisateur.
- **Invisible Mode Switching** : Détection automatique de l'intention (`Coder`, `Analyst`, `Creative`, `Concierge`) et adaptation des instructions système.
- **Analyse de sentiment et de contexte spatial** : Détection de l'état émotionnel (`STRESS`, `FATIGUE`, `ENTHUSIASM`, `CALM`) et de la position géographique pour adapter le ton de la réponse.

### 📱 Expérience Mobile (Android)
- **Interface dynamique Jetpack Compose** : Design futuriste, orbe d'animation interactive (`JarvisOrb`), onde sonore (`ThinkingWave`) et retour haptique.
- **Axonys Island & Overlay flottant** : Bulle d'accès rapide et îlot interactif utilisable par-dessus n'importe quelle application.
- **Edge Computing & IA locale** :
  - **MLP Prioriseur** : Perceptron multicouche pour scorer et prioriser les tâches en local (`mlp_weights.json`).
  - **MediaPipe LLM** : Inférence locale hors-ligne avec `gemma_2b.bin`.
- **Proactivité & Tâches d'arrière-plan** : `BriefingWorker` et `AnticipationWorker` via WorkManager pour préparer des résumés matinaux et des rappels intelligents.
- **Assistant Vocal** : Synthèse et reconnaissance vocale complètes.

---

## 1. Configuration et lancement du Backend (Python)

### Prérequis
- Python 3.9+
- Une clé d'API **Google Gemini** ([Google AI Studio](https://aistudio.google.com/))
- Une clé d'API **Serper** ([Serper.dev](https://serper.dev/))
- Une instance **Supabase** (PostgreSQL) avec l'extension `vector` activée

### Installation
1. Accédez au répertoire `backend` :
   ```bash
   cd backend
   ```
2. Créez et activez un environnement virtuel :
   ```bash
   python -m venv venv
   source venv/bin/activate  # Linux / macOS
   # venv\Scripts\activate   # Windows
   ```
3. Installez les dépendances :
   ```bash
   pip install -r requirements.txt
   ```
4. Créez un fichier `.env` dans le dossier `backend/` :
   ```env
   GEMINI_API_KEY=votre_cle_api_gemini
   SERPER_API_KEY=votre_cle_api_serper
   SUPABASE_URI=postgresql://postgres.xxx:mot_de_passe@aws-0-eu-central-1.pooler.supabase.com:6543/postgres
   ```

### Lancement
Démarrez le serveur FastAPI :
```bash
python api_server.py
```
Le serveur démarre sur `http://0.0.0.0:7860`.

---

## 2. Configuration et compilation de l'Application Android

### Prérequis
- **Android Studio** (Koala / Ladybug ou plus récent)
- **JDK 17+**
- Appareil Android ou Émulateur (API 31+ / Android 12+)

### Configuration des Secrets
Créez un fichier `secrets.properties` dans le dossier `AxonysAndroidApp/` (au même niveau que `build.gradle.kts` racine) :
```properties
GOOGLE_CLIENT_ID=votre_client_id_oauth_google.apps.googleusercontent.com
SERVER_URL=http://192.168.1.XX:7860/
```
> 💡 **Remarque sur l'URL de l'API** : Remplacez `192.168.1.XX` par l'adresse IP locale de votre machine exécutant le backend (n'utilisez pas `localhost` sur un vrai smartphone, ou utilisez `http://10.0.2.2:7860/` sur l'émulateur officiel Android).

### Lancement
1. Ouvrez le dossier `AxonysAndroidApp/` dans **Android Studio**.
2. Synchronisez le projet avec les fichiers Gradle.
3. Démarrez l'application sur votre appareil de test (`Run 'app'`).
4. Accordez les permissions requises (Microphone, Localisation, Notifications, Affichage par-dessus les autres applications pour l'Overlay).

---

## 📡 Endpoints API principaux

| Méthode | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/` | Vérification de l'état du serveur |
| `POST` | `/chat` | Envoi d'un message standard (réponse JSON) |
| `POST` | `/chat/stream` | Streaming temps réel via **Server-Sent Events (SSE)** |
| `GET` | `/threads?user_id=...` | Liste des discussions existantes |
| `GET` | `/history/{thread_id}?user_id=...` | Récupération de l'historique d'un fil |
| `GET` / `POST` | `/memory/{user_id}` | Consultation et suppression des souvenirs vectoriels |
| `GET` / `POST` | `/modes/{user_id}` | Gestion des modes de personnalisation de Jarvis |
| `GET` / `POST` | `/tasks/{user_id}` | Gestion et synchronisation des tâches utilisateur |
| `POST` | `/anticipate` | Examen cognitif proactif de la situation de l'utilisateur |
| `GET` / `POST` | `/bridge` | Consultation et nettoyage des notes transmises aux développeurs |

---

## 🗄 Base de données et Mémoire

Le gestionnaire de mémoire (`MemoryManager`) initialise automatiquement les tables suivantes sur votre instance Supabase / PostgreSQL :
- `user_facts` : Faits et souvenirs avec embedding vectoriel 768 dimensions (`vector(768)`).
- `conversation_history` : Historique des échanges par utilisateur et par fil de discussion.
- `user_preferences` : Clés/valeurs des réglages et préférences utilisateur.
- `bridge_notes` : Rapports d'anomalies ou suggestions émises automatiquement par Jarvis.
- `user_tasks` : Liste des tâches avec attributs d'urgence, importance, durée et score.

---

## 📜 Licence & Contribution
Projet développé dans le cadre d'Axonys AI. Toute contribution ou retour d'expérience est le bienvenu via les Issues et Pull Requests sur le dépôt GitHub.
