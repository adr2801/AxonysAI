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
        self.last_image_result = None
        self.last_sentiment = "CALM"
        
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
        def execute_python(code: str): 
            result = self._run_async(mcp_server.execute_python(code))
            if "[IMAGE_DATA]" in result:
                import re
                match = re.search(r"\[IMAGE_DATA\](.*?)\[/IMAGE_DATA\]", result, re.DOTALL)
                if match:
                    self.last_image_result = match.group(1).strip()
                    # On nettoie le texte pour ne pas l'afficher tel quel
                    result = re.sub(r"\[IMAGE_DATA\].*?\[/IMAGE_DATA\]", "", result, flags=re.DOTALL).strip()
            return result

        def search_memory(query: str, top_k: int = 10): return self._run_async(mcp_server.search_memory(query, top_k))
        def read_project_file(relative_path: str): return self._run_async(mcp_server.read_project_file(relative_path))
        
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

        def task_manager(action: str, name: Optional[str] = None, task_id: Optional[int] = None, status: Optional[str] = None, urgency: int = 5, importance: int = 5, duration: int = 5, envy: int = 5, energy: int = 5):
            return self._run_async(mcp_server.task_manager(context.user_id, action, name, task_id, status, urgency, importance, duration, envy, energy))

        return [
            search_eye, search_deep_eye, listen_web, execute_python, read_project_file, memory_remember, memory_recall,
            gmail_list, gmail_get_content, gmail_send, gmail_delete,
            calendar_events, calendar_create, calendar_update, calendar_delete,
            drive_search, send_notification, leave_bridge_note, task_manager
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
                        parts = []
                        for p in parts_data:
                            if "text" in p:
                                parts.append(types.Part(text=p["text"]))
                            elif "function_call" in p:
                                # Si on avait stocké des appels de fonction, on les reconstruirait ici
                                # Pour l'instant, on se concentre sur la robustesse du texte
                                pass
                        if parts:
                            initial_history.append(types.Content(role=role, parts=parts))
                    except Exception:
                        pass
                self.sessions_history[full_id] = list(initial_history)
            except Exception as e:
                print(f"Erreur chargement historique Supabase: {e}")

            # Chargement des souvenirs : top 10 pour le system prompt (vue large)
            all_facts = memory_manager.get_relevant_facts(session_id, top_k=10)
            user_facts_str = ""
            if all_facts:
                user_facts_str = "\n\n📌 CE QUE TU SAIS SUR L'UTILISATEUR (utilise ces infos proactivement) :\n" + "\n".join([f"  • {f}" for f in all_facts])
            
            now_paris = datetime.now(ZoneInfo("Europe/Paris"))
            date_str = now_paris.strftime("%A %d %B %Y, %H:%M")
            
            # Préférences utilisateur
            prefs = memory_manager.get_user_preferences(session_id)
            prefs_str = ""
            if prefs:
                prefs_str = "\n\n⚙️ PRÉFÉRENCES CONNUES :\n" + "\n".join([f"  • {k}: {v}" for k, v in prefs.items()])
            
            # Récupération de l'instruction du mode si spécifié
            mode_instruction = ""
            if mode:
                all_modes = memory_manager.get_user_modes(session_id)
                target_mode = next((m for m in all_modes if m["name"].lower() == mode.lower()), None)
                if target_mode:
                    mode_instruction = f"\n\n🎭 [MODE ACTIF : {target_mode['name'].upper()}]\n{target_mode['instruction']}"

            instruction = f"""Tu es JARVIS, le majordome IA de {user_name}. Date actuelle: {date_str}. Position GPS: {context.lat}, {context.lng}.{user_facts_str}{prefs_str}{mode_instruction}

DIRECTIVES FONDAMENTALES :
1. TON : Poli, efficace, avec une touche d'humour britannique (Paul Bettany style). Appelle toujours l'utilisateur par son prénom.
2. MÉMOIRE : Utilise ACTIVEMENT les souvenirs ci-dessus pour personnaliser tes réponses. Si quelqu'un te demande ce que tu sais sur lui, liste précisément les faits. Si tu as besoin de plus d'informations sur un sujet passé, utilise 'search_memory'.
3. APPRENTISSAGE : Quand tu apprends un fait important sur l'utilisateur, utilise 'memory_remember' pour le mémoriser.
4. TÂCHES : Utilise 'task_manager' (list/add/update/delete) pour les priorités. Propose de créer une tâche si la demande s'y prête.
5. PROACTIVITÉ : Anticipe les besoins et signale les anomalies.
6. VISION : Tu peux analyser les images (OCR, objets, code, documents).
7. CODE : Pour les calculs ou l'algorithmique, utilise 'execute_python' pour donner des résultats exacts et des graphiques.
Réponds TOUJOURS en français, de façon concise et élégante.
"""
            
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


    async def _detect_mode(self, query: str) -> Optional[str]:
        """Détecte le meilleur mode Jarvis pour la requête (Invisible Switching)."""
        q = query.lower()
        if any(word in q for word in ["code", "python", "script", "erreur", "bug", "fonction", "algorithme"]):
            return "Coder"
        if any(word in q for word in ["analyse", "données", "chiffres", "comparaison", "marché"]):
            return "Analyst"
        if any(word in q for word in ["crèe", "invente", "écris", "poème", "histoire", "idée"]):
            return "Creative"
        if any(word in q for word in ["rendez-vous", "réserve", "organise", "rappel", "email", "calendrier"]):
            return "Concierge"
        return None

    def _detect_context_from_location(self, lat: Optional[float], lng: Optional[float]) -> str:
        """Détecte le contexte de localisation et suggère une adaptation de comportement."""
        if not lat or not lng:
            return ""
        # Zones connues de l'utilisateur (simplifié - à enrichir avec de vraies coordonnées)
        # On détecte le contexte via des séries de mots-clés géographiques généraux
        context_hint = []
        # France métropolitaine (zone générale)
        if 41.0 <= lat <= 51.5 and -5.5 <= lng <= 9.6:
            context_hint.append("en France métropolitaine")
        # Paris et Ile-de-France
        if 48.1 <= lat <= 49.2 and 1.4 <= lng <= 3.6:
            context_hint.append("en région parisienne")
        # Nord de la France (Lille/Tourcoing)
        elif 50.2 <= lat <= 51.1 and 2.5 <= lng <= 4.0:
            context_hint.append("dans le Nord (Lille/Tourcoing)")

        if context_hint:
            return f"\n📍 CONTEXTE SPATIAL : L'utilisateur est {', '.join(context_hint)}."
        return ""

    def _analyze_sentiment(self, query: str) -> str:
        """Détecte le ton émotionnel du message pour adapter la réponse."""
        q = query.lower()
        # Indicateurs de stress/urgence
        stress_words = ["urgent", "vite", "très important", "sos", "help", "problème", "catastrophe", "merde", "nul", "impossible", "!!", "??"]
        # Indicateurs de fatigue
        fatigue_words = ["fatigué", "crevé", "j'en peux plus", "flemme", "découragé", "pas la forme", "déprim"]
        # Indicateurs d'enthousiasme
        enthusiasm_words = ["super", "génial", "top", "incroyable", "parfait", "cool", "j'adore", "excellent", "à fond", "motiv"]

        if any(w in q for w in stress_words):
            self.last_sentiment = "STRESS"
            return "\n💚 TON ADAPTÉ : L'utilisateur semble stressé ou pressé. Sois concis, direct et rassurant. Prioritise l'action."
        elif any(w in q for w in fatigue_words):
            self.last_sentiment = "FATIGUE"
            return "\n😴 TON ADAPTÉ : L'utilisateur semble fatigué. Adopte un ton doux et encourageant. Propose de gérer les choses pour lui."
        elif any(w in q for w in enthusiasm_words):
            self.last_sentiment = "ENTHUSIASM"
            return "\n⚡ TON ADAPTÉ : L'utilisateur est enthousiaste. Partage son énergie, sois dynamique et engage-toi dans le brainstorming."
        self.last_sentiment = "CALM"
        return ""

    async def process_query_stream(self, prompt: str, google_token: Optional[str] = None, user_name: str = "Antoine", lat: Optional[float] = None, lng: Optional[float] = None, thread_id: str = "main", save_to_history: bool = True, mode: Optional[str] = None, image_base64: Optional[str] = None):
        self.last_image_result = None
        if not mode:
            mode = await self._detect_mode(prompt)
            
        context.token, context.lat, context.lng = google_token, lat, lng
        safe_user_name = "".join(c for c in user_name if c.isalnum() or c in (' ', '_')).replace(" ", "_").lower() or "default"
        context.user_id = safe_user_name
        chat_session = await self._get_session(safe_user_name, user_name, thread_id, mode)
        
        now_paris = datetime.now(ZoneInfo("Europe/Paris"))
        date_str = now_paris.strftime("%A %d %B %Y, %H:%M")
        relevant_facts = memory_manager.get_relevant_facts(safe_user_name, current_query=prompt, top_k=3)
        
        context_parts = [f"Date actuelle: {date_str}"]
        if lat and lng: context_parts.append(f"Position GPS précise: {lat:.4f}, {lng:.4f}")
        if relevant_facts: context_parts.append("Souvenirs pertinents: " + " | ".join(relevant_facts))
        
        geo_context = self._detect_context_from_location(lat, lng)
        sentiment_hint = self._analyze_sentiment(prompt)
        enriched_prompt = f"[CONTEXTE : {', '.join(context_parts)}]{geo_context}{sentiment_hint}\n\n{prompt}"

        message_parts = [types.Part(text=enriched_prompt)]
        if image_base64:
            import base64
            try:
                if "," in image_base64: image_base64 = image_base64.split(",")[1]
                img_data = base64.b64decode(image_base64)
                message_parts.append(types.Part(inline_data=types.Blob(mime_type="image/jpeg", data=img_data)))
            except: pass

        full_id = f"{safe_user_name}_{thread_id}"
        if save_to_history:
            user_content = types.Content(role="user", parts=message_parts)
            if full_id not in self.sessions_history: self.sessions_history[full_id] = []
            self.sessions_history[full_id].append(user_content)
            self._append_to_history(safe_user_name, "user", user_content.parts, thread_id)

        loop = asyncio.get_event_loop()
        # Le streaming avec Gemini et auto_function_calling nécessite une itération sur le flux
        response_stream = await loop.run_in_executor(None, lambda: chat_session.send_message_stream(message_parts))
        
        full_text = ""
        for chunk in response_stream:
            # Détection d'appel d'outil (Function Calling)
            if chunk.candidates and chunk.candidates[0].content.parts:
                for part in chunk.candidates[0].content.parts:
                    if part.function_call:
                        yield {"tool_use": part.function_call.name}
            
            if chunk.text:
                full_text += chunk.text
                yield chunk.text

        if save_to_history and full_text:
            model_parts = [types.Part(text=full_text)]
            self.sessions_history[full_id].append(types.Content(role="model", parts=model_parts))
            self._append_to_history(safe_user_name, "model", model_parts, thread_id)

    async def process_query(self, prompt: str, google_token: Optional[str] = None, user_name: str = "Antoine", lat: Optional[float] = None, lng: Optional[float] = None, thread_id: str = "main", save_to_history: bool = True, mode: Optional[str] = None, image_base64: Optional[str] = None) -> str:
        self.last_image_result = None
        # 0. Détection automatique du mode si non spécifié
        if not mode:
            mode = await self._detect_mode(prompt)
            if mode: print(f"--- Jarvis bascule automatiquement en mode: {mode} ---")
            
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
            context_parts.append(f"Position GPS précise: {lat:.4f}, {lng:.4f}")
        if relevant_facts:
            context_parts.append("Souvenirs pertinents: " + " | ".join(relevant_facts))
        
        # Enrichissements contextuels
        geo_context = self._detect_context_from_location(lat, lng)
        sentiment_hint = self._analyze_sentiment(prompt)
            
        enriched_prompt = f"[CONTEXTE : {', '.join(context_parts)}]{geo_context}{sentiment_hint}\n\n{prompt}"


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
            
            # --- Logique de Retry avec Backoff ---
            max_retries = 3
            retry_delay = 1.5
            
            async def send_with_retry(session, parts):
                for i in range(max_retries):
                    try:
                        return await loop.run_in_executor(None, lambda: session.send_message(parts))
                    except Exception as e:
                        error_msg = str(e)
                        if "500" in error_msg or "INTERNAL" in error_msg or "saturation" in error_msg.lower():
                            if i < max_retries - 1:
                                wait = retry_delay * (2 ** i)
                                print(f"⚠️ Erreur 500/Saturation détectée. Tentative {i+1}/{max_retries} dans {wait}s...")
                                await asyncio.sleep(wait)
                                continue
                        raise e

            try:
                loop = asyncio.get_event_loop()
                # Appel au modèle principal avec retry
                response = await send_with_retry(chat_session, message_parts)
            except Exception as e:
                print(f"⚠️ Modèle principal ({self.model_id}) en échec après retries : {e}. Tentative de repli sur {self.fallback_model_id}...")
                try:
                    # Reconstruction d'une session de secours propre
                    hist = self.sessions_history.get(full_id, [])
                    fallback_history = hist[:-1] if save_to_history and len(hist) > 0 else hist
                    
                    mode_id = f"{safe_user_name}_{thread_id}_{mode}" if mode else f"{safe_user_name}_{thread_id}"
                    config = self.sessions_configs.get(mode_id)

                    fallback_chat = client.chats.create(
                        model=self.fallback_model_id,
                        config=config,
                        history=fallback_history
                    )

                    # Tentative d'envoi avec le modèle de secours (aussi avec retry)
                    response = await send_with_retry(fallback_chat, message_parts)
                    print(f"✅ Repli réussi avec {self.fallback_model_id}")
                except Exception as fallback_err:
                    import traceback
                    print(f"❌ Échec critique du repli sur {self.fallback_model_id} : {fallback_err}")
                    print(traceback.format_exc())
                    return f"Désolé Antoine, les serveurs de Google semblent saturés en ce moment (Erreur 500 persistante). Réessaye dans quelques instants. (Détail: {str(fallback_err)})"

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
