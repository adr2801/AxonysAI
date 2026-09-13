import os
import json
# pyrefly: ignore [missing-import]
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel
from fastapi.middleware.cors import CORSMiddleware
from dotenv import load_dotenv
import sys
from pathlib import Path
BASE_DIR = Path(__file__).parent.absolute()
env_path = BASE_DIR / ".env"
load_dotenv(dotenv_path=env_path)

# Import du moteur Jarvis
try:
    from jarvis_engine import JarvisEngine, memory_manager
except ImportError as e:
    print(f"Erreur critique : Impossible d'importer jarvis_engine.py ({e})")
    sys.exit(1)

app = FastAPI()

# Instance globale du moteur Jarvis
# On le crée une fois au démarrage pour garder l'historique (ou on peut le recréer par session)
jarvis = JarvisEngine()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["https://axonysai.free.fr", "http://localhost:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

from typing import Optional

class ChatRequest(BaseModel):
    prompt: str
    google_token: Optional[str] = None
    user_id: str = "default_user"
    user_name: Optional[str] = "Utilisateur"
    lat: Optional[float] = None
    lng: Optional[float] = None
    thread_id: Optional[str] = "main"
    mode: Optional[str] = None
    image_base64: Optional[str] = None


class ModeRequest(BaseModel):
    name: str
    instruction: str
    icon: Optional[str] = "💎"
    color: Optional[str] = "#4285F4"

class TaskRequest(BaseModel):
    name: str
    urgency: int = 5
    importance: int = 5
    duration: int = 5
    envy: int = 5
    energy: int = 5
    status: Optional[str] = "pending"
    score: Optional[float] = 0.0


@app.get("/")
async def root():
    return {"status": "Online", "message": "Jarvis Native Server is running!"}

from fastapi.responses import StreamingResponse

@app.post("/chat/stream")
async def chat_stream(request: ChatRequest):
    async def event_generator():
        try:
            # Envoi du sentiment détecté dès le début
            yield f"data: {json.dumps({'sentiment': jarvis.last_sentiment})}\n\n"

            async for chunk in jarvis.process_query_stream(
                prompt=request.prompt,
                google_token=request.google_token,
                user_id=request.user_id,
                user_name=request.user_name,
                lat=request.lat,
                lng=request.lng,
                thread_id=request.thread_id,
                mode=request.mode,
                image_base64=request.image_base64
            ):
                if isinstance(chunk, dict) and "tool_use" in chunk:
                    yield f"data: {json.dumps({'tool_use': chunk['tool_use']})}\n\n"
                elif isinstance(chunk, str):
                    yield f"data: {json.dumps({'chunk': chunk})}\n\n"
            
            # Envoi final avec métadonnées (image_result, sentiment)
            yield f"data: {json.dumps({'done': True, 'image_result': jarvis.last_image_result, 'sentiment': jarvis.last_sentiment})}\n\n"
        except Exception as e:
            yield f"data: {json.dumps({'error': str(e), 'done': True})}\n\n"

    return StreamingResponse(event_generator(), media_type="text/event-stream")

@app.post("/chat")
async def chat(request: ChatRequest):
    try:
        # On passe le token reçu de l'application Android au moteur Jarvis
        response_text = await jarvis.process_query(
            request.prompt, 
            google_token=request.google_token, 
            user_id=request.user_id,
            user_name=request.user_name,
            lat=request.lat,
            lng=request.lng,
            thread_id=request.thread_id or "main",
            mode=request.mode,
            image_base64=request.image_base64
        )



        
        return {
            "response": response_text, 
            "image_result": jarvis.last_image_result,
            "sentiment": jarvis.last_sentiment
        }
    
    except Exception as e:
        import traceback
        error_trace = traceback.format_exc()
        print(f"!!! ERREUR CRITIQUE API JARVIS (/chat) !!!\n{error_trace}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/chat/{user_id}/delete")
async def delete_chat_message(user_id: str, request: Request):
    try:
        data = await request.json()
        thread_id = data.get("thread_id", "main")
        content = data.get("content")
        from jarvis_engine import memory_manager
        memory_manager.delete_message(user_id, thread_id, content)
        return {"status": "deleted"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/chat/{user_id}/clear")
async def clear_chat_thread(user_id: str, request: Request):
    try:
        data = await request.json()
        thread_id = data.get("thread_id", "main")
        from jarvis_engine import memory_manager
        memory_manager.clear_thread(user_id, thread_id)
        return {"status": "cleared"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/anticipate")
async def anticipate(request: ChatRequest):
    try:
        # 1. Analyse cognitive proactive (Jarvis réfléchit)
        prompt = (
            "Fais un auto-examen de ma situation actuelle (calendrier, mails, trafic). "
            "Si tu détectes un besoin d'action ou une info capitale, utilise 'schedule_smart_reminder' pour plus tard ou 'send_notification' pour tout de suite. "
            "Si tout est sous contrôle, réponds simplement 'RAS'."
        )
        await jarvis.process_query(
            prompt, 
            google_token=request.google_token, 
            user_name=request.user_name,
            lat=request.lat,
            lng=request.lng,
            save_to_history=False
        )

        # 2. Récupération des notifications à envoyer maintenant (programmées précédemment)
        from jarvis_engine import memory_manager
        pending = memory_manager.get_pending_notifications(request.user_name.lower() if request.user_name else "antoine")

        return {"status": "Analysis complete", "notifications": pending}
    except Exception as e:
        import traceback
        print(f"!!! ERREUR CRITIQUE API JARVIS (/anticipate) !!!\n{traceback.format_exc()}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/notifications")
async def get_notifications(user_id: str):

    # On importe context ici pour accéder aux notifs stockées dans jarvis_engine
    from jarvis_engine import context
    notifs = context.pending_notifications.copy()
    return {"notifications": notifs}

@app.post("/notifications/clear")
async def clear_notifications(user_id: str):

    from jarvis_engine import context
    context.pending_notifications.clear()
    return {"status": "Cleared"}

@app.get("/history/{thread_id}")
async def get_thread_history(thread_id: str, user_id: str):
    """Récupère l'historique complet d'un thread spécifique pour un utilisateur donné."""
    try:
        safe_user_id = memory_manager.normalize_user_id(user_id)
        with memory_manager.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "SELECT role, content FROM conversation_history WHERE user_id = %s AND thread_id = %s ORDER BY timestamp ASC",
                    (safe_user_id, thread_id)
                )
                rows = cursor.fetchall()
                
        import re
        formatted_history = []
        for role, content_json in rows:
            text = ""
            try:
                parts = json.loads(content_json)
                for p in parts:
                    if isinstance(p, dict) and "text" in p:
                        text += p["text"]
            except Exception:
                pass
            
            # Nettoyage de tout le contexte injecté au début du message
            text = re.sub(r'^\[CONTEXTE :.*?\n\n', '', text, flags=re.DOTALL)
            if "Génère un briefing matinal" in text or "Analyse mes notifications" in text:
                continue
                
            if text.strip():
                formatted_history.append({
                    "text": text.strip(),
                    "isUser": role == "user"
                })
        return {"history": formatted_history}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/threads")
async def list_threads(user_id: str):
    """Liste tous les threads de discussion disponibles depuis Supabase pour l'utilisateur."""
    try:
        safe_user_id = memory_manager.normalize_user_id(user_id)
        with memory_manager.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT DISTINCT thread_id FROM conversation_history WHERE user_id = %s", (safe_user_id,))
                rows = cursor.fetchall()
                
        threads = {row[0] for row in rows}
        threads.add("main")
        sorted_threads = sorted(list(threads), key=lambda x: (x != "main", x))
        return {"threads": sorted_threads}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/preferences/{user_id}")
async def get_preferences(user_id: str):
    try:
        prefs = memory_manager.get_user_preferences(user_id)
        return {"preferences": prefs}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

class PreferenceRequest(BaseModel):
    preference_key: str
    preference_value: str

@app.post("/preferences/{user_id}")
async def set_preference(user_id: str, request: PreferenceRequest):
    try:
        memory_manager.set_user_preference(user_id, request.preference_key, request.preference_value)
        return {"status": "success"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/memory/{user_id}")
async def get_memory(user_id: str):
    try:
        facts = memory_manager.get_all_facts(user_id)
        return {"facts": facts}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/memory/delete")
async def delete_memory_fact(request: Request):
    try:
        data = await request.json()
        user_id = data.get("user_id")
        if not user_id:
            raise HTTPException(status_code=400, detail="user_id is required")
        fact = data.get("fact")

        if not fact:
            raise HTTPException(status_code=400, detail="Fact text is required")
        memory_manager.delete_fact(user_id, fact)
        return {"status": "deleted"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/modes/{user_id}")
async def get_modes(user_id: str):
    try:
        modes = memory_manager.get_user_modes(user_id)
        return {"modes": modes}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/modes/{user_id}")
async def set_mode(user_id: str, request: ModeRequest):
    try:
        memory_manager.set_user_mode(user_id, request.name, request.instruction, request.icon, request.color)
        return {"status": "success"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/modes/{user_id}/delete")
async def delete_mode(user_id: str, request: Request):
    try:
        data = await request.json()
        mode_name = data.get("name")
        memory_manager.delete_user_mode(user_id, mode_name)
        return {"status": "deleted"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/tasks/{user_id}")
async def get_tasks(user_id: str):
    try:
        uid = memory_manager.normalize_user_id(user_id)
        with memory_manager.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "SELECT name, score, urgency, importance, duration, envy, energy, status, id FROM user_tasks WHERE user_id = %s ORDER BY score DESC",
                    (uid,)
                )
                rows = cursor.fetchall()
        
        tasks = []
        for r in rows:
            tasks.append({
                "name": r[0],
                "score": (r[1] or 0.0) * 100, # Conversion en % pour l'app
                "urgency": r[2],
                "importance": r[3],
                "duration": r[4],
                "envy": r[5],
                "energy": r[6],
                "status": r[7],
                "id": r[8]
            })
        return {"tasks": tasks}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/tasks/{user_id}")
async def add_task(user_id: str, request: TaskRequest):
    try:
        uid = memory_manager.normalize_user_id(user_id)
        with memory_manager.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO user_tasks (user_id, name, score, urgency, importance, duration, envy, energy, status) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)",
                    (uid, request.name, (request.score or 0.0) / 100.0, request.urgency, request.importance, request.duration, request.envy, request.energy, request.status)
                )
            conn.commit()
        return {"status": "success"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/tasks/{user_id}/delete")
async def delete_task(user_id: str, request: Request):
    try:
        uid = memory_manager.normalize_user_id(user_id)
        data = await request.json()
        task_id = data.get("id")
        task_name = data.get("name")
        with memory_manager.get_conn() as conn:
            with conn.cursor() as cursor:
                if task_id:
                    cursor.execute("DELETE FROM user_tasks WHERE user_id = %s AND id = %s", (uid, task_id))
                else:
                    cursor.execute("DELETE FROM user_tasks WHERE user_id = %s AND name = %s", (uid, task_name))
            conn.commit()
        return {"status": "deleted"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=7860, timeout_keep_alive=120)
