import os
import requests
import base64
import json
from datetime import datetime
from dotenv import load_dotenv
from typing import List, Dict, Any, Optional
from google import genai
from google.genai import types
from memory_manager import MemoryManager
from pathlib import Path

# Configuration
BASE_DIR = Path(__file__).parent.absolute()
env_path = BASE_DIR / ".env"
load_dotenv(dotenv_path=env_path)

API_KEY = os.getenv("GEMINI_API_KEY")
SERPER_API_KEY = os.getenv("SERPER_API_KEY")
BRIDGE_FILE = os.path.join(BASE_DIR, "jarvis_notes.json")
MEMORIES_DIR = os.path.join(BASE_DIR, "memories")

if not API_KEY:
    raise ValueError("GEMINI_API_KEY non trouvée dans le fichier .env")

client = genai.Client(api_key=API_KEY)
memory_manager = MemoryManager(db_path=os.path.join(BASE_DIR, "jarvis_memory.db"))

# --- CONTEXTE GLOBAL ---
class UserContext:
    token: Optional[str] = None
    user_id: str = "default"
    pending_notifications: List[Dict[str, str]] = []
    lat: Optional[float] = None
    lng: Optional[float] = None
    current_thread: str = "main"

context = UserContext()

# --- OUTILS DE BASE ---

def get_weather_and_traffic() -> str:
    """Récupère la météo et les infos de trafic locales en fonction de la position GPS de l'utilisateur."""
    if not context.lat or not context.lng: return "Position GPS non disponible."
    if not SERPER_API_KEY: return "Erreur: SERPER_API_KEY manquante."
    
    query = f"météo et trafic à {context.lat}, {context.lng}"
    url = "https://google.serper.dev/search"
    payload = {"q": query, "gl": "fr", "hl": "fr"}
    headers = {'X-API-KEY': SERPER_API_KEY, 'Content-Type': 'application/json'}
    try:
        response = requests.post(url, headers=headers, json=payload)
        results = response.json()
        snippet = results.get("answerBox", {}).get("answer") or results.get("organic", [{}])[0].get("snippet")
        return f"Infos locales : {snippet}"
    except Exception as e: return f"Erreur : {str(e)}"

def search_web(query: str) -> str:
    """Recherche des informations sur internet."""
    if not SERPER_API_KEY: return "Erreur: SERPER_API_KEY manquante."
    url = "https://google.serper.dev/search"
    payload = {"q": query, "gl": "fr", "hl": "fr"}
    headers = {'X-API-KEY': SERPER_API_KEY, 'Content-Type': 'application/json'}
    try:
        response = requests.post(url, headers=headers, json=payload)
        results = response.json()
        snippets = [f"- {res.get('title')}: {res.get('snippet')}" for res in results.get("organic", [])[:3]]
        return "\n".join(snippets) if snippets else "Aucun résultat trouvé."
    except Exception as e: return f"Erreur : {str(e)}"

def send_notification(title: str, message: str) -> str:
    """Envoie une alerte proactive sur le téléphone de l'utilisateur et l'ajoute au chat."""
    notif = {"title": title, "message": message, "timestamp": datetime.now().isoformat()}
    context.pending_notifications.append(notif)
    
    # Ajout à l'historique de chat persistant
    memory_manager.add_to_history(context.user_id, "model", f"🔔 **{title}**\n{message}")

    return f"Alerte '{title}' envoyée."

# --- OUTILS DE MÉMOIRE ---

def remember(info: str) -> str:
    """Sauvegarde une information importante sur l'utilisateur, ses préférences ou des faits à long terme."""
    memory_manager.save_fact(context.user_id, info)
    return f"Je l'ai noté : '{info}'."

def recall_long_term() -> str:
    """Récupère toutes les informations importantes mémorisées sur l'utilisateur."""
    facts = memory_manager.get_relevant_facts(context.user_id)
    if not facts: return "Aucun souvenir particulier."
    return "\n".join([f"- {f}" for f in facts])

# --- OUTILS DE COMMUNICATION DÉVELOPPEUR ---

def send_note_to_developer(message: str) -> str:
    """Envoie une suggestion technique, un bug ou une idée d'amélioration au développeur (Antigravity)."""
    try:
        notes = []
        if os.path.exists(BRIDGE_FILE):
            with open(BRIDGE_FILE, 'r', encoding='utf-8') as f:
                notes = json.load(f)
        note = {
            "from": "Jarvis",
            "message": message,
            "timestamp": datetime.now().isoformat(),
            "read": False
        }
        notes.append(note)
        with open(BRIDGE_FILE, 'w', encoding='utf-8') as f:
            json.dump(notes, f, ensure_ascii=False, indent=2)
        return f"Note transmise au développeur : '{message}'."
    except Exception as e:
        return f"Erreur pont développeur : {str(e)}"

# --- OUTILS GOOGLE ---

def gmail_list_emails(max_results: int = 5) -> str:
    """Liste les derniers emails reçus avec expéditeur et sujet."""
    if not context.token: return "Compte non connecté."
    headers = {"Authorization": f"Bearer {context.token}"}
    try:
        res = requests.get(f"https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults={max_results}", headers=headers).json()
        messages = res.get("messages", [])
        if not messages: return "Aucun mail."
        summary = []
        for msg in messages:
            m = requests.get(f"https://gmail.googleapis.com/gmail/v1/users/me/messages/{msg['id']}", headers=headers).json()
            h = m.get("payload", {}).get("headers", [])
            subject = next((i["value"] for i in h if i["name"].lower() == "subject"), "Sans sujet")
            sender = next((i["value"] for i in h if i["name"].lower() == "from"), "Inconnu")
            summary.append(f"ID: {msg['id']} | De: {sender} | Sujet: {subject}")
        return "\n".join(summary)
    except Exception as e: return str(e)

def gmail_get_email_content(message_id: str) -> str:
    """Récupère le contenu complet d'un email par son ID."""
    if not context.token: return "Compte non connecté."
    headers = {"Authorization": f"Bearer {context.token}"}
    try:
        m = requests.get(f"https://gmail.googleapis.com/gmail/v1/users/me/messages/{message_id}", headers=headers).json()
        return m.get("snippet", "")
    except Exception as e: return str(e)

def gmail_send_email(to: str, subject: str, body: str) -> str:
    """Envoie un email via Gmail au nom de l'utilisateur."""
    if not context.token: return "Erreur : Compte Google non connecté."
    headers = {"Authorization": f"Bearer {context.token}", "Content-Type": "application/json"}
    try:
        raw_message = f"To: {to}\r\nSubject: {subject}\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n{body}"
        encoded = base64.urlsafe_b64encode(raw_message.encode('utf-8')).decode('utf-8')
        res = requests.post(
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
            headers=headers,
            json={"raw": encoded}
        )
        if res.status_code in [200, 204]:
            return f"Email envoyé à {to} avec le sujet '{subject}'."
        return f"Erreur envoi : {res.text}"
    except Exception as e: return f"Erreur Gmail : {str(e)}"

def gmail_delete_email(message_id: str) -> str:
    """Supprime définitivement un email de la boîte mail par son ID."""
    if not context.token: return "Erreur : Compte Google non connecté."
    headers = {"Authorization": f"Bearer {context.token}"}
    try:
        res = requests.delete(
            f"https://gmail.googleapis.com/gmail/v1/users/me/messages/{message_id}",
            headers=headers
        )
        if res.status_code == 204:
            return f"Email {message_id} supprimé avec succès."
        return f"Erreur suppression : {res.text}"
    except Exception as e: return f"Erreur Gmail : {str(e)}"

def drive_search_files(query: str) -> str:
    """Cherche des fichiers sur Google Drive par nom (ex: 'facture', 'assurance')."""
    if not context.token: return "Erreur : Compte Google non connecté."
    url = f"https://www.googleapis.com/drive/v3/files?q=name contains '{query}'&fields=files(id, name, mimeType, webViewLink)"
    headers = {"Authorization": f"Bearer {context.token}"}
    try:
        res = requests.get(url, headers=headers)
        files = res.json().get("files", [])
        if not files: return f"Aucun fichier trouvé pour '{query}'."
        return "\n".join([f"Nom: {f['name']} | Type: {f['mimeType']} | Lien: {f['webViewLink']}" for f in files[:5]])
    except Exception as e: return f"Erreur Drive : {str(e)}"

def get_directions(destination: str) -> str:
    """Calcule l'itinéraire et le temps de trajet depuis la position GPS actuelle."""
    if not context.lat or not context.lng: return "Position GPS non disponible."
    if not SERPER_API_KEY: return "Erreur: SERPER_API_KEY manquante."
    query = f"itinéraire et temps de trajet de {context.lat},{context.lng} vers {destination}"
    url = "https://google.serper.dev/search"
    payload = {"q": query, "gl": "fr", "hl": "fr"}
    headers = {'X-API-KEY': SERPER_API_KEY, 'Content-Type': 'application/json'}
    try:
        response = requests.post(url, headers=headers, json=payload)
        results = response.json()
        info = results.get("answerBox", {}).get("answer") or results.get("organic", [{}])[0].get("snippet")
        return f"Itinéraire vers {destination} : {info}"
    except Exception as e: return f"Erreur : {str(e)}"

def calendar_list_events(max_results: int = 5) -> str:
    if not context.token: return "Compte non connecté."
    headers = {"Authorization": f"Bearer {context.token}"}
    try:
        now = datetime.utcnow().isoformat() + 'Z'
        res = requests.get(f"https://www.googleapis.com/calendar/v3/calendars/primary/events?timeMin={now}&maxResults={max_results}&singleEvents=true&orderBy=startTime", headers=headers).json()
        events = res.get("items", [])
        if not events: return "Aucun événement."
        return "\n".join([f"{e.get('start', {}).get('dateTime')} : {e.get('summary')}" for e in events])
    except Exception as e: return str(e)

def calendar_create_event(summary: str, start_time: str, end_time: str) -> str:
    if not context.token: return "Erreur : Compte Google non connecté."
    url = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
    headers = {"Authorization": f"Bearer {context.token}", "Content-Type": "application/json"}
    event = {'summary': summary, 'start': {'dateTime': start_time}, 'end': {'dateTime': end_time}}
    try:
        res = requests.post(url, headers=headers, json=event)
        return f"Événement '{summary}' créé." if res.status_code == 200 else f"Erreur : {res.text}"
    except Exception as e: return str(e)

def calendar_delete_event(event_id: str) -> str:
    if not context.token: return "Erreur : Compte Google non connecté."
    url = f"https://www.googleapis.com/calendar/v3/calendars/primary/events/{event_id}"
    headers = {"Authorization": f"Bearer {context.token}"}
    try:
        res = requests.delete(url, headers=headers)
        return "Événement supprimé." if res.status_code == 204 else f"Erreur : {res.text}"
    except Exception as e: return str(e)

def auto_anticipate() -> str:
    """Outil interne d'anticipation."""
    summary = []
    events = calendar_list_events(max_results=3)
    if "Aucun" not in events:
        summary.append(f"Calendrier: {events}")
        traffic = get_weather_and_traffic()
        summary.append(f"Trafic: {traffic}")
    emails = gmail_list_emails(max_results=3)
    if "Aucun" not in emails: summary.append(f"Mails: {emails}")
    return "\n".join(summary)

# --- MOTEUR COGNITIF ---

class JarvisEngine:
    def __init__(self, model_name="gemma-4-31b-it"):
        self.model_id = model_name
        self.tools = [
            search_web, send_notification, get_weather_and_traffic, get_directions,
            auto_anticipate, remember, recall_long_term, send_note_to_developer,
            gmail_list_emails, gmail_get_email_content, gmail_send_email, gmail_delete_email,
            calendar_list_events, calendar_create_event, calendar_delete_event,
            drive_search_files
        ]
        self.sessions = {}

    def _get_session(self, session_id: str, user_name: str, thread_id: str = "main"):
        full_id = f"{session_id}_{thread_id}"
        if full_id not in self.sessions:
            # Récupération de l'historique depuis le MemoryManager
            # Note: Pour une implémentation complète, il faudrait ajouter une méthode 
            # get_history(user_id, thread_id) au MemoryManager.
            # En attendant, nous continuons d'utiliser le fichier JSON existant pour l'historique de chat.
            history_file = os.path.join(MEMORIES_DIR, f"history_{session_id}_{thread_id}.json")
            initial_history = []
            if os.path.exists(history_file):
                try:
                    with open(history_file, 'r', encoding='utf-8') as f:
                        full_history = json.load(f)
                        initial_history = full_history[-15:]
                except Exception as e:
                    print(f"Erreur historique thread {thread_id}: {e}")

            # RÉCUPÉRATION DES SOUVENIRS INITIAUX
            facts = memory_manager.get_relevant_facts(session_id, top_k=5)
            user_facts = ""
            if facts:
                user_facts = "\nSOUVENIRS IMPORTANTS SUR L'UTILISATEUR :\n" + "\n".join([f"- {f}" for f in facts])

            date_str = datetime.now().strftime("%A %d %B %Y, %H:%M")
            if thread_id == "main":
                instruction = f"Tu es Jarvis, le majordome de {user_name}. Thread: Général. Date: {date_str}. Position: {context.lat}, {context.lng}."
            else:
                instruction = f"Tu es Jarvis, assistant spécialisé pour {user_name}. Thread: {thread_id}. Date: {date_str}."
            
            instruction += f"\n{user_facts}\nCAPACITÉS: Mémoire persistante, Proactivité, Workspace Google, Itinéraires. Agis avec l'autonomie et le style d'un majordome (Tony Stark style)."

            config = types.GenerateContentConfig(
                system_instruction=instruction,
                tools=self.tools,
                automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=False)
            )
            self.sessions[full_id] = client.chats.create(model=self.model_id, config=config, history=initial_history)
        return self.sessions[full_id]

    def _save_history(self, session_id: str, thread_id: str = "main"):
        # Nous continuons d'utiliser le système de fichiers pour l'historique conversationnel pour l'instant.
        full_id = f"{session_id}_{thread_id}"
        if full_id in self.sessions:
            chat_session = self.sessions[full_id]
            history_file = os.path.join(MEMORIES_DIR, f"history_{session_id}_{thread_id}.json")
            try:
                serializable_history = []
                for msg in chat_session.history:
                    parts = []
                    for p in msg.parts:
                        if p.text:
                            parts.append({"text": p.text})
                        elif p.function_call:
                            parts.append({"text": f"[Appel de fonction : {p.function_call.name}]"})
                        elif p.function_response:
                            parts.append({"text": f"[Réponse de fonction : {p.function_response.name}]"})
                    
                    if parts:
                        serializable_history.append({
                            "role": msg.role,
                            "parts": parts
                        })
                
                # S'assurer que le répertoire existe
                if not os.path.exists(MEMORIES_DIR):
                    os.makedirs(MEMORIES_DIR)
                
                with open(history_file, 'w', encoding='utf-8') as f:
                    json.dump(serializable_history, f, ensure_ascii=False, indent=2)
            except Exception as e:
                print(f"Erreur sauvegarde {thread_id}: {e}")

    def process_query(self, prompt: str, google_token: Optional[str] = None, user_name: str = "Antoine", lat: Optional[float] = None, lng: Optional[float] = None, thread_id: str = "main") -> str:
        context.token = google_token
        context.lat = lat
        context.lng = lng
        context.user_id = google_token[:15] if google_token else "default"
        session_id = context.user_id
        chat_session = self._get_session(session_id, user_name, thread_id)
        
        # --- RECHERCHE SÉMANTIQUE ---
        # On recherche dynamiquement si la base de données contient des faits pertinents pour le prompt
        relevant_facts = memory_manager.get_relevant_facts(session_id, current_query=prompt, top_k=3)
        enriched_prompt = prompt
        if relevant_facts:
            # On injecte silencieusement le contexte trouvé devant la question de l'utilisateur
            context_prefix = "[CONTEXTE MÉMOIRE PERTINENT : " + " | ".join(relevant_facts) + "]\n"
            enriched_prompt = context_prefix + prompt

        try:
            response = chat_session.send_message(enriched_prompt)
            self._save_history(session_id, thread_id)
            return response.text
        except Exception as e:
            return f"Erreur cognitive {thread_id}: {str(e)}"
