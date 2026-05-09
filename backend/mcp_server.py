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
