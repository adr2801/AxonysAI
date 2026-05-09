import os
import sys
import json
import asyncio
import threading
from datetime import datetime
from dotenv import load_dotenv
from typing import List, Dict, Any, Optional
from google import genai
from google.genai import types
from memory_manager import MemoryManager
from pathlib import Path
from zoneinfo import ZoneInfo
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

# Configuration
BASE_DIR = Path(__file__).parent.absolute()
load_dotenv(dotenv_path=BASE_DIR / ".env")

API_KEY = os.getenv("GEMINI_API_KEY")
MEMORIES_DIR = os.path.join(BASE_DIR, "memories")
BRIDGE_FILE = os.path.join(BASE_DIR, "jarvis_bridge.json")

if not API_KEY:
    raise ValueError("GEMINI_API_KEY non trouvée")

if not os.path.exists(BRIDGE_FILE):
    with open(BRIDGE_FILE, 'w', encoding='utf-8') as f:
        json.dump([], f)

client = genai.Client(api_key=API_KEY)
memory_manager = MemoryManager(db_path=os.path.join(BASE_DIR, "jarvis_memory.db"))

class UserContext:
    def __init__(self):
        self.token = None
        self.lat = None
        self.lng = None
        self.user_id = "default"
        self.pending_notifications = []

context = UserContext()

class JarvisEngine:
    def __init__(self, model_name="gemma-4-31b-it"):
        self.model_id = model_name
        self.fallback_model_id = "gemma-4-26b-a4b-it"


        self.sessions = {}
        self.sessions_configs = {}
        self.sessions_history = {}
        
    def _run_async(self, coro):
        try:
            return asyncio.run(coro)
        except Exception as e:
            return f"Erreur bridge : {str(e)}"

    async def call_mcp_tool_async(self, name: str, arguments: dict) -> str:
        env = os.environ.copy()
        # On s'assure que le chemin python est dans l'environnement
        env["PYTHONPATH"] = str(BASE_DIR)
        server_params = StdioServerParameters(
            command=sys.executable,
            args=[str(BASE_DIR / "mcp_server.py")],
            env=env
        )
        async with stdio_client(server_params) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                result = await session.call_tool(name, arguments)
                return result.content[0].text

    def _get_tools(self):
        import mcp_server
        
        # Wrappers pour Gemini (appels directs aux fonctions asynchrones de mcp_server)
        def search_eye(query: str): return self._run_async(mcp_server.search_eye(query))
        def search_deep_eye(query: str): return self._run_async(mcp_server.search_deep_eye(query))
        def listen_web(url: str): return self._run_async(mcp_server.listen_web(url))
        
        def memory_remember(fact: str): return self._run_async(mcp_server.memory_remember(context.user_id, fact))
        def memory_recall(query: str): return self._run_async(mcp_server.memory_recall(context.user_id, query))
        
        # Gmail
        def gmail_list(count: int = 5): return self._run_async(mcp_server.gmail_list(context.token, count))
        def gmail_get_content(message_id: str): return self._run_async(mcp_server.gmail_get_content(context.token, message_id))
        def gmail_send(recipient: str, subject: str, body: str): return self._run_async(mcp_server.gmail_send(context.token, recipient, subject, body))
        def gmail_delete(message_id: str): return self._run_async(mcp_server.gmail_delete(context.token, message_id))
        
        # Calendrier
        def calendar_events(): return self._run_async(mcp_server.calendar_events(context.token))
        def calendar_create(summary: str, start_time: str, end_time: str): return self._run_async(mcp_server.calendar_create(context.token, summary, start_time, end_time))
        def calendar_update(event_id: str, summary: Optional[str] = None, start_time: Optional[str] = None, end_time: Optional[str] = None): return self._run_async(mcp_server.calendar_update(context.token, event_id, summary, start_time, end_time))
        def calendar_delete(event_id: str): return self._run_async(mcp_server.calendar_delete(context.token, event_id))

        # Drive, Notifs & Bridge
        def drive_search(query: str): return self._run_async(mcp_server.drive_search(context.token, query))
        def send_notification(title: str, message: str): 
            context.pending_notifications.append({"title": title, "message": message, "timestamp": datetime.now().isoformat()})
            return self._run_async(mcp_server.send_notification(title, message))
        def leave_bridge_note(title: str, content: str, category: str = "INFO"):
            """Laisse une note dans le fichier bridge (jarvis_bridge.json) destinée à l'IA de développement (Antigravity).
            Utilise cet outil quand tu détectes : un bug, une anomalie, une idée d'amélioration, ou une observation importante sur le comportement du système.
            L'IA de dev lira ce fichier pour comprendre ce qui s'est passé en production sans que tu aies à tout réexpliquer."""
            return self._run_async(mcp_server.leave_bridge_note(title, content, category))

        return [
            search_eye, search_deep_eye, listen_web, memory_remember, memory_recall,
            gmail_list, gmail_get_content, gmail_send, gmail_delete,
            calendar_events, calendar_create, calendar_update, calendar_delete,
            drive_search, send_notification, leave_bridge_note
        ]


    async def _get_session(self, session_id: str, user_name: str, thread_id: str = "main", mode: Optional[str] = None):
        full_id = f"{session_id}_{thread_id}"
        # Si le mode change, on doit recréer la session pour mettre à jour l'instruction système
        mode_id = f"{full_id}_{mode}" if mode else full_id
        
        if mode_id not in self.sessions:
            # Chargement de l'historique depuis Supabase
            initial_history = []
            try:
                with memory_manager.get_conn() as conn:
                    with conn.cursor() as cursor:
                        cursor.execute(
                            """SELECT role, content FROM conversation_history 
                               WHERE user_id = %s AND thread_id = %s 
                               ORDER BY timestamp ASC LIMIT 30""",
                            (session_id, thread_id)
                        )
                        rows = cursor.fetchall()
                for role, content_json in rows:
                    try:
                        parts_data = json.loads(content_json)
                        parts = [types.Part(text=p["text"]) for p in parts_data if "text" in p]
                        if parts:
                            initial_history.append(types.Content(role=role, parts=parts))
                    except Exception:
                        pass
                self.sessions_history[full_id] = list(initial_history)
            except Exception as e:
                print(f"Erreur chargement historique Supabase: {e}")

            facts = memory_manager.get_relevant_facts(session_id, top_k=5)
            user_facts = "\nSOUVENIRS :\n" + "\n".join([f"- {f}" for f in facts]) if facts else ""
            now_paris = datetime.now(ZoneInfo("Europe/Paris"))
            date_str = now_paris.strftime("%A %d %B %Y, %H:%M")
            
            # Récupération de l'instruction du mode si spécifié
            mode_instruction = ""
            if mode:
                all_modes = memory_manager.get_user_modes(session_id)
                target_mode = next((m for m in all_modes if m["name"].lower() == mode.lower()), None)
                if target_mode:
                    mode_instruction = f"\n[MODE {target_mode['name'].upper()}] : {target_mode['instruction']}"

            instruction = f"""Tu es Jarvis, le majordome IA d'Antoine. Date: {date_str}. Position: {context.lat}, {context.lng}.
{user_facts}{mode_instruction}
Outils disponibles : Gmail, Calendrier, Drive, Recherche web, Mémoire, Notifications.
Réponds toujours en français, de façon concise et utile."""
            
            config = types.GenerateContentConfig(
                system_instruction=instruction, 
                tools=self._get_tools(), 
                automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=False)
            )
            
            if full_id not in self.sessions_history: self.sessions_history[full_id] = initial_history
            self.sessions[mode_id] = client.chats.create(model=self.model_id, config=config, history=initial_history)
            self.sessions_configs[mode_id] = config
        return self.sessions[mode_id]



    def _append_to_history(self, session_id: str, role: str, content_parts: List[Any], thread_id: str = "main"):
        try:
            parts_json = json.dumps([{"text": p.text} for p in content_parts if hasattr(p, 'text') and p.text])
            with memory_manager.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute(
                        "INSERT INTO conversation_history (user_id, thread_id, role, content) VALUES (%s, %s, %s, %s)",
                        (session_id, thread_id, role, parts_json)
                    )
                conn.commit()
        except Exception as e:
            print(f"Erreur append historique Supabase: {e}")


    async def process_query(self, prompt: str, google_token: Optional[str] = None, user_name: str = "Antoine", lat: Optional[float] = None, lng: Optional[float] = None, thread_id: str = "main", save_to_history: bool = True, mode: Optional[str] = None, image_base64: Optional[str] = None) -> str:
        context.token, context.lat, context.lng = google_token, lat, lng
        safe_user_name = "".join(c for c in user_name if c.isalnum() or c in (' ', '_')).replace(" ", "_").lower() or "default"
        context.user_id = safe_user_name
        chat_session = await self._get_session(safe_user_name, user_name, thread_id, mode)
        # On injecte l'heure réelle de Paris à chaque message
        now_paris = datetime.now(ZoneInfo("Europe/Paris"))
        date_str = now_paris.strftime("%A %d %B %Y, %H:%M")
        
        relevant_facts = memory_manager.get_relevant_facts(safe_user_name, current_query=prompt, top_k=3)
        
        context_parts = [f"Date actuelle: {date_str}"]
        if lat and lng:
            context_parts.append(f"Position actuelle: {lat}, {lng}")
        if relevant_facts:
            context_parts.append("Souvenirs: " + " | ".join(relevant_facts))
            
        enriched_prompt = f"[CONTEXTE : {', '.join(context_parts)}]\n\n{prompt}"


        # Construction du message avec image si présente
        message_parts = [types.Part(text=enriched_prompt)]
        if image_base64:
            import base64
            try:
                # Retrait du header data:image/jpeg;base64, si présent
                if "," in image_base64:
                    image_base64 = image_base64.split(",")[1]
                img_data = base64.b64decode(image_base64)
                message_parts.append(types.Part(inline_data=types.Blob(mime_type="image/jpeg", data=img_data)))
            except Exception as e:
                print(f"Erreur décodage image: {e}")

        try:
            full_id = f"{safe_user_name}_{thread_id}"
            if save_to_history:
                if full_id not in self.sessions_history: self.sessions_history[full_id] = []
                user_content = types.Content(role="user", parts=message_parts)
                self.sessions_history[full_id].append(user_content)
                self._append_to_history(safe_user_name, "user", user_content.parts, thread_id)
            
            try:
                loop = asyncio.get_event_loop()
                response = await loop.run_in_executor(None, lambda: chat_session.send_message(message_parts))
            except Exception as e:
                print(f"⚠️ Modèle principal ({self.model_id}) saturé ou erreur : {e}. Tentative de repli sur {self.fallback_model_id}...")
                try:
                    # On utilise l'historique local pour reconstruire la session de secours
                    # On retire le dernier message car send_message va l'ajouter
                    hist = self.sessions_history.get(full_id, [])
                    if save_to_history and len(hist) > 0:
                        fallback_history = hist[:-1]
                    else:
                        fallback_history = hist

                    # Récupération de la config stockée
                    mode_id = f"{safe_user_name}_{thread_id}_{mode}" if mode else f"{safe_user_name}_{thread_id}"
                    config = self.sessions_configs.get(mode_id)

                    fallback_chat = client.chats.create(
                        model=self.fallback_model_id,
                        config=config,
                        history=fallback_history
                    )

                    response = await loop.run_in_executor(None, lambda: fallback_chat.send_message(message_parts))
                    print(f"✅ Repli réussi avec {self.fallback_model_id}")
                except Exception as fallback_err:
                    print(f"❌ Échec du repli sur {self.fallback_model_id} : {fallback_err}")
                    return f"Erreur cognitive critique : Les deux modèles ({self.model_id} et {self.fallback_model_id}) sont indisponibles. Détail : {str(e)}"

            if save_to_history:

                model_parts = [types.Part(text=response.text)]
                self.sessions_history[full_id].append(types.Content(role="model", parts=model_parts))
                self._append_to_history(safe_user_name, "model", model_parts, thread_id)
            
            return response.text


        except Exception as e:
            import traceback
            error_trace = traceback.format_exc()
            print(f"!!! ERREUR COGNITIVE JARVIS !!!\n{error_trace}")
            return f"Erreur cognitive: {str(e)}"
