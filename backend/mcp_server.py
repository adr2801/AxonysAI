import os
import json
import httpx
import base64
from datetime import datetime
from mcp.server.fastmcp import FastMCP
from dotenv import load_dotenv
from pathlib import Path
from typing import Optional

# Chargement des variables d'environnement
BASE_DIR = Path(__file__).parent.absolute()
load_dotenv(dotenv_path=BASE_DIR / ".env")

# Clés API
SERPER_API_KEY = os.getenv("SERPER_API_KEY")
TINYFISH_API_KEY = os.getenv("TINYFISH_API_KEY")
DB_PATH = os.path.join(BASE_DIR, "jarvis_memory.db")

import asyncio
import sys
import subprocess

# Initialisation du serveur MCP
mcp = FastMCP("Jarvis-Ultimate-Server")

# --- OUTILS DE RECHERCHE ---

@mcp.tool()
async def search_eye(query: str) -> str:
    """L'OEIL : Recherche rapide via Serper.dev."""
    if not SERPER_API_KEY: return "Clé SERPER manquante."
    url = "https://google.serper.dev/search"
    headers = {'X-API-KEY': SERPER_API_KEY, 'Content-Type': 'application/json'}
    async with httpx.AsyncClient() as client:
        res = await client.post(url, headers=headers, json={"q": query, "gl": "fr", "hl": "fr"})
        snippets = [r.get("snippet", "") for r in res.json().get("organic", [])[:3]]
        return "\n".join(snippets) if snippets else "Pas de résultats."

@mcp.tool()
async def search_deep_eye(query: str) -> str:
    """L'OEIL PROFOND : Recherche avancée via TinyFish."""
    if not TINYFISH_API_KEY: return "Clé TINYFISH manquante."
    url = "https://api.tinyfish.ai/v1/search"
    headers = {"Authorization": f"Bearer {TINYFISH_API_KEY}"}
    async with httpx.AsyncClient() as client:
        res = await client.post(url, headers=headers, json={"query": query, "language": "fr"})
        results = res.json().get("results", [])
        return "\n\n".join([f"Source: {r.get('title')}\n{r.get('snippet')}" for r in results[:3]])

@mcp.tool()
async def listen_web(url: str) -> str:
    """L'OREILLE : Lit le contenu complet d'une page via TinyFish Fetch."""
    if not TINYFISH_API_KEY: return "Clé TINYFISH manquante."
    async with httpx.AsyncClient() as client:
        res = await client.post("https://api.tinyfish.ai/v1/fetch", 
                                headers={"Authorization": f"Bearer {TINYFISH_API_KEY}"},
                                json={"url": url, "format": "markdown"})
        return res.json().get("markdown", "Vide")[:4000]

@mcp.tool()
async def execute_python(code: str) -> str:
    """LE LAB : Exécute du code Python et retourne le résultat. 
    Supporte matplotlib : si vous utilisez plt.show(), le graphique sera capturé et renvoyé.
    Idéal pour les maths, l'algorithmique (NSI) et les calculs complexes."""
    
    # Injection pour capturer les graphiques matplotlib si utilisés
    if "matplotlib" in code or "plt." in code:
        code = """
import matplotlib
matplotlib.use('Agg') # Mode sans interface graphique
import matplotlib.pyplot as plt
import io, base64

# --- Code original ---
""" + code + """
# --- Capture du graphique ---
if plt.get_fignums():
    buf = io.BytesIO()
    plt.savefig(buf, format='png', bbox_inches='tight')
    buf.seek(0)
    img_str = base64.b64encode(buf.read()).decode()
    print(f"\\n[IMAGE_DATA]{img_str}[/IMAGE_DATA]")
    plt.close('all')
"""

    try:
        process = await asyncio.create_subprocess_exec(
            sys.executable, "-c", code,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        stdout, stderr = await process.communicate()
        output = stdout.decode().strip()
        error = stderr.decode().strip()
        if error: return f"Sortie: {output}\nErreur: {error}"
        return output if output else "Code exécuté avec succès."
    except Exception as e:
        return f"Échec de l'exécution : {str(e)}"

@mcp.tool()
async def read_project_file(relative_path: str) -> str:
    """LIT UN FICHIER DU PROJET (Code, Cours, Notes). 
    Le chemin doit être relatif à la racine du projet CortexAI."""
    try:
        # On remonte d'un cran car on est dans 'backend'
        root_dir = BASE_DIR.parent
        file_path = root_dir / relative_path
        
        if not file_path.exists():
            return f"Fichier non trouvé : {relative_path}"
            
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
            return content[:5000] # Limite pour éviter de saturer le contexte
    except Exception as e:
        return f"Erreur de lecture : {str(e)}"

# --- OUTILS DE MÉMOIRE ---

@mcp.tool()
async def memory_remember(user_id: str, fact: str) -> str:
    """Enregistre un fait important dans la mémoire persistante de Jarvis."""
    from memory_manager import MemoryManager
    mm = MemoryManager(db_path=DB_PATH)
    mm.save_fact(user_id, fact)
    return f"Je me souviendrai de : '{fact}'"

@mcp.tool()
async def memory_recall(user_id: str, query: str) -> str:
    """Récupère les souvenirs les plus pertinents."""
    from memory_manager import MemoryManager
    mm = MemoryManager(db_path=DB_PATH)
    facts = mm.get_relevant_facts(user_id, current_query=query, top_k=5)
    return "\n".join([f"- {f}" for f in facts]) if facts else "Aucun souvenir trouvé."

@mcp.tool()
async def memory_set_preference(user_id: str, preference_key: str, preference_value: str) -> str:
    """Enregistre ou met à jour une préférence explicite de l'utilisateur (ex: 'boisson_favorite', 'café noir')."""
    from memory_manager import MemoryManager
    mm = MemoryManager()
    mm.set_user_preference(user_id, preference_key, preference_value)
    return f"Préférence enregistrée : {preference_key} = {preference_value}"

@mcp.tool()
async def memory_get_preferences(user_id: str) -> str:
    """Récupère toutes les préférences explicites connues de l'utilisateur."""
    from memory_manager import MemoryManager
    mm = MemoryManager()
    prefs = mm.get_user_preferences(user_id)
    return "\n".join([f"- {k}: {v}" for k, v in prefs.items()]) if prefs else "Aucune préférence enregistrée."

@mcp.tool()
async def schedule_smart_reminder(user_id: str, title: str, message: str, scheduled_time: str) -> str:
    """Planifie une notification push proactive pour l'utilisateur. 
    scheduled_time doit être au format ISO (ex: '2026-05-08T15:30:00')."""
    from memory_manager import MemoryManager
    mm = MemoryManager()
    mm.schedule_notification(user_id, title, message, scheduled_time)
    return f"Rappel planifié pour {scheduled_time} : {title}"


@mcp.tool()
async def search_memory(query: str, top_k: int = 10) -> str:
    """
    Effectue une recherche sémantique approfondie dans les souvenirs de l'utilisateur.
    Utilise la recherche vectorielle pour trouver les faits les plus pertinents.
    """
    from memory_manager import MemoryManager
    from jarvis_engine import context
    
    user_id = context.user_id or "default"
    mm = MemoryManager()
    facts = mm.get_relevant_facts(user_id, current_query=query, top_k=top_k)
    
    if not facts:
        return "Aucun souvenir pertinent trouvé pour cette recherche."
    
    return "Souvenirs trouvés :\n- " + "\n- ".join(facts)


# --- OUTILS GMAIL ---

@mcp.tool()
async def gmail_list(google_token: str, count: int = 5) -> str:
    """Liste les derniers emails reçus."""
    headers = {"Authorization": f"Bearer {google_token}"}
    async with httpx.AsyncClient() as client:
        res = await client.get(f"https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults={count}", headers=headers)
        messages = res.json().get("messages", [])
        if not messages: return "Aucun mail."
        results = []
        for m in messages:
            m_data = await client.get(f"https://gmail.googleapis.com/gmail/v1/users/me/messages/{m['id']}", headers=headers)
            results.append(f"ID: {m['id']} | {m_data.json().get('snippet')}")
        return "\n".join(results)

@mcp.tool()
async def gmail_get_content(google_token: str, message_id: str) -> str:
    """Lit le contenu d'un email spécifique."""
    headers = {"Authorization": f"Bearer {google_token}"}
    async with httpx.AsyncClient() as client:
        res = await client.get(f"https://gmail.googleapis.com/gmail/v1/users/me/messages/{message_id}", headers=headers)
        return res.json().get("snippet", "Contenu indisponible.")

@mcp.tool()
async def gmail_send(google_token: str, recipient: str, subject: str, body: str) -> str:
    """Envoie un nouvel email."""
    headers = {"Authorization": f"Bearer {google_token}", "Content-Type": "application/json"}
    raw_msg = f"To: {recipient}\r\nSubject: {subject}\r\n\r\n{body}"
    encoded_msg = base64.urlsafe_b64encode(raw_msg.encode()).decode()
    async with httpx.AsyncClient() as client:
        res = await client.post("https://gmail.googleapis.com/gmail/v1/users/me/messages/send", 
                                headers=headers, json={"raw": encoded_msg})
        return f"Email envoyé à {recipient} (ID: {res.json().get('id')})"

@mcp.tool()
async def gmail_delete(google_token: str, message_id: str) -> str:
    """Supprime un email."""
    headers = {"Authorization": f"Bearer {google_token}"}
    async with httpx.AsyncClient() as client:
        await client.post(f"https://gmail.googleapis.com/gmail/v1/users/me/messages/{message_id}/trash", headers=headers)
        return "Email mis à la corbeille."

# --- OUTILS CALENDRIER ---

@mcp.tool()
async def calendar_events(google_token: str) -> str:
    """Récupère les prochains événements."""
    headers = {"Authorization": f"Bearer {google_token}"}
    now = datetime.utcnow().isoformat() + 'Z'
    url = f"https://www.googleapis.com/calendar/v3/calendars/primary/events?timeMin={now}&maxResults=5&singleEvents=true&orderBy=startTime"
    async with httpx.AsyncClient() as client:
        res = await client.get(url, headers=headers)
        items = res.json().get("items", [])
        return "\n".join([f"ID: {i['id']} | {i.get('start', {}).get('dateTime')} : {i.get('summary')}" for i in items]) if items else "Vide."

@mcp.tool()
async def calendar_create(google_token: str, summary: str, start_time: str, end_time: str) -> str:
    """Crée un événement (Format date: 'YYYY-MM-DDTHH:MM:SS')."""
    headers = {"Authorization": f"Bearer {google_token}", "Content-Type": "application/json"}
    payload = {
        "summary": summary, 
        "start": {"dateTime": start_time, "timeZone": "Europe/Paris"}, 
        "end": {"dateTime": end_time, "timeZone": "Europe/Paris"}
    }
    async with httpx.AsyncClient() as client:
        res = await client.post("https://www.googleapis.com/calendar/v3/calendars/primary/events", headers=headers, json=payload)
        return f"Événement '{summary}' créé."


@mcp.tool()
async def calendar_update(google_token: str, event_id: str, summary: Optional[str] = None, start_time: Optional[str] = None, end_time: Optional[str] = None) -> str:
    """Modifie un événement existant. (Format date: 'YYYY-MM-DDTHH:MM:SS')."""
    headers = {"Authorization": f"Bearer {google_token}", "Content-Type": "application/json"}
    payload = {}
    if summary: payload["summary"] = summary
    if start_time: payload["start"] = {"dateTime": start_time, "timeZone": "Europe/Paris"}
    if end_time: payload["end"] = {"dateTime": end_time, "timeZone": "Europe/Paris"}
    
    async with httpx.AsyncClient() as client:
        res = await client.patch(f"https://www.googleapis.com/calendar/v3/calendars/primary/events/{event_id}", 
                                 headers=headers, json=payload)

        if res.status_code == 200:
            return f"Événement {event_id} mis à jour avec succès."
        else:
            return f"Erreur lors de la modification : {res.text}"

@mcp.tool()
async def calendar_delete(google_token: str, event_id: str) -> str:

    """Supprime un événement du calendrier."""
    headers = {"Authorization": f"Bearer {google_token}"}
    async with httpx.AsyncClient() as client:
        await client.delete(f"https://www.googleapis.com/calendar/v3/calendars/primary/events/{event_id}", headers=headers)
        return "Événement supprimé."

# --- OUTILS DRIVE ET NOTIFICATIONS ---

@mcp.tool()
async def drive_search(google_token: str, query: str) -> str:
    """Recherche des fichiers sur Google Drive."""
    headers = {"Authorization": f"Bearer {google_token}"}
    async with httpx.AsyncClient() as client:
        res = await client.get(f"https://www.googleapis.com/drive/v3/files?q=name contains '{query}'", headers=headers)
        files = res.json().get("files", [])
        return "\n".join([f"- {f['name']} (ID: {f['id']})" for f in files]) if files else "Aucun fichier."

@mcp.tool()
async def search_traffic(location: str) -> str:
    """Analyse le trafic routier en temps réel pour une localisation donnée."""
    if not SERPER_API_KEY: return "Clé SERPER manquante."
    query = f"trafic routier temps réel {location} accidents bouchons"
    url = "https://google.serper.dev/search"
    headers = {'X-API-KEY': SERPER_API_KEY, 'Content-Type': 'application/json'}
    async with httpx.AsyncClient() as client:
        res = await client.post(url, headers=headers, json={"q": query, "gl": "fr", "hl": "fr"})
        results = res.json()
        # On essaie de récupérer les infos de la "map" ou des premiers résultats
        snippets = [r.get("snippet", "") for r in results.get("organic", [])[:3]]
        return f"État du trafic à {location} :\n" + ("\n".join(snippets) if snippets else "Pas d'incidents majeurs signalés.")

@mcp.tool()
async def send_to_dev(content: str, category: str = "NOTE") -> str:
    """Envoie des informations, du code ou des tâches vers le journal de développement."""
    dev_file = os.path.join(BASE_DIR, "dev_workspace.md")
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    formatted_content = f"\n\n### [{category}] - {timestamp}\n{content}\n---"
    
    with open(dev_file, 'a', encoding='utf-8') as f:
        f.write(formatted_content)
    return f"Informations transmises au développement dans 'dev_workspace.md'."

@mcp.tool()
async def task_manager(user_id: str, action: str, name: Optional[str] = None, task_id: Optional[int] = None, status: Optional[str] = None, urgency: int = 5, importance: int = 5, duration: int = 5, envy: int = 5, energy: int = 5) -> str:
    """Gère la liste de tâches priorisées (MLP). 
    Actions: 'list', 'add', 'update', 'delete'.
    Pour 'add' et 'update', les paramètres urgency/importance/etc. permettent de calculer le score de priorité (0-10)."""
    try:
        from memory_manager import MemoryManager
        mm = MemoryManager()
        
        if action == "list":
            with mm.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("SELECT id, name, score, status FROM user_tasks WHERE user_id = %s ORDER BY score DESC", (user_id,))
                    rows = cursor.fetchall()
                    if not rows: return "Aucune tâche pour le moment."
                    return "\n".join([f"[{r[0]}] {r[1]} - Priorité: {int(r[2]*100)}% - Statut: {r[3]}" for r in rows])
        
        elif action == "add":
            if not name: return "Nom de tâche manquant."
            # On laisse le backend calculer le score lors de l'insertion ou l'appli lors du sync
            # Mais ici on peut faire un calcul rapide (simulé pour l'instant, sera affiné par l'API)
            score = (urgency + importance + (10-duration) + envy + energy) / 50.0
            with mm.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute(
                        "INSERT INTO user_tasks (user_id, name, score, urgency, importance, duration, envy, energy) VALUES (%s, %s, %s, %s, %s, %s, %s, %s) RETURNING id",
                        (user_id, name, score, urgency, importance, duration, envy, energy)
                    )
                    new_id = cursor.fetchone()[0]
                conn.commit()
            return f"Tâche '{name}' ajoutée (ID: {new_id}, Score: {int(score*100)}%)."

        elif action == "update":
            if not task_id: return "ID de tâche manquant pour la mise à jour."
            updates = []
            params = []
            if name: 
                updates.append("name = %s")
                params.append(name)
            if status:
                updates.append("status = %s")
                params.append(status)
            
            # Recalcul du score si l'un des paramètres MLP est fourni
            # On récupère les anciennes valeurs si non fournies ? 
            # Pour faire simple on recalcule avec ce qui est passé
            score = (urgency + importance + (10-duration) + envy + energy) / 50.0
            updates.append("score = %s, urgency = %s, importance = %s, duration = %s, envy = %s, energy = %s")
            params.extend([score, urgency, importance, duration, envy, energy])
            
            params.append(task_id)
            query = f"UPDATE user_tasks SET {', '.join(updates)} WHERE id = %s"
            
            with mm.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute(query, tuple(params))
                conn.commit()
            return f"Tâche {task_id} mise à jour (Nouveau score: {int(score*100)}%)."

        elif action == "delete":
            if not task_id: return "ID de tâche manquant."
            with mm.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("DELETE FROM user_tasks WHERE id = %s", (task_id,))
                conn.commit()
            return f"Tâche {task_id} supprimée."
            
        return "Action non reconnue."
    except Exception as e:
        return f"Erreur TaskManager : {str(e)}"

@mcp.tool()
async def leave_bridge_note(title: str, content: str, category: str = "INFO") -> str:
    """Laisse une note dans le fichier pont (bridge) pour qu'Antoine puisse la lire dans l'application.
    Utilise cet outil pour signaler des tâches importantes, des bugs détectés, ou des idées à développer."""
    try:
        from memory_manager import MemoryManager
        mm = MemoryManager()
        with mm.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO bridge_notes (title, message, category) VALUES (%s, %s, %s)",
                    (title, content, category)
                )
            conn.commit()
        return f"Note '{title}' ajoutée au bridge de Supabase avec succès."
    except Exception as e:
        return f"Erreur lors de l'ajout de la note : {str(e)}"

@mcp.tool()
async def send_notification(title: str, message: str) -> str:
    """Envoie une notification push."""

    notif_file = os.path.join(BASE_DIR, "pending_notifications.json")
    notifs = []
    if os.path.exists(notif_file):
        with open(notif_file, 'r') as f: notifs = json.load(f)
    notifs.append({"title": title, "message": message, "timestamp": datetime.now().isoformat()})
    with open(notif_file, 'w') as f: json.dump(notifs, f)
    return f"Notif envoyée."

if __name__ == "__main__":
    mcp.run()
