import os
import json
from fastapi import FastAPI, HTTPException
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
    from jarvis_engine import JarvisEngine
except ImportError as e:
    print(f"Erreur critique : Impossible d'importer jarvis_engine.py ({e})")
    sys.exit(1)

app = FastAPI()

# Instance globale du moteur Jarvis
# On le crée une fois au démarrage pour garder l'historique (ou on peut le recréer par session)
jarvis = JarvisEngine()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

from typing import Optional

class ChatRequest(BaseModel):
    prompt: str
    google_token: Optional[str] = None
    user_name: Optional[str] = "Antoine"
    lat: Optional[float] = None
    lng: Optional[float] = None
    thread_id: Optional[str] = "main"

@app.get("/")
async def root():
    return {"status": "Online", "message": "Jarvis Native Server is running!"}

@app.post("/chat")
async def chat(request: ChatRequest):
    try:
        # On passe le token reçu de l'application Android au moteur Jarvis
        response_text = jarvis.process_query(
            request.prompt, 
            google_token=request.google_token, 
            user_name=request.user_name,
            lat=request.lat,
            lng=request.lng,
            thread_id=request.thread_id or "main"
        )
        
        return {"response": response_text}
    
    except Exception as e:
        print(f"Erreur API Jarvis : {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/anticipate")
async def anticipate(request: ChatRequest):
    try:
        prompt = (
            "Fais un auto-examen de ma situation actuelle (calendrier, mails, trafic). "
            "Si tu détectes un besoin d'action ou une info capitale, utilise 'send_notification'. "
            "Si tout est sous contrôle, réponds simplement 'RAS'."
        )
        jarvis.process_query(
            prompt, 
            google_token=request.google_token, 
            user_name=request.user_name,
            lat=request.lat,
            lng=request.lng
        )
        return {"status": "Analysis complete"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/notifications")
async def get_notifications():
    # On importe context ici pour accéder aux notifs stockées dans jarvis_engine
    from jarvis_engine import context
    notifs = context.pending_notifications.copy()
    return {"notifications": notifs}

@app.post("/notifications/clear")
async def clear_notifications():
    from jarvis_engine import context
    context.pending_notifications.clear()
    return {"status": "Cleared"}

@app.get("/history/{thread_id}")
async def get_thread_history(thread_id: str, google_token: Optional[str] = None):
    """Récupère l'historique complet d'un thread spécifique."""
    try:
        user_id = google_token[:15] if google_token else "default"
        from jarvis_engine import MEMORIES_DIR
        history_file = os.path.join(MEMORIES_DIR, f"history_{user_id}_{thread_id}.json")
        
        if not os.path.exists(history_file):
            return {"history": []}
            
        with open(history_file, 'r', encoding='utf-8') as f:
            history = json.load(f)
            
        # Simplification pour l'application mobile (on ne renvoie que le texte et le rôle)
        import re
        formatted_history = []
        for msg in history:
            text = ""
            for p in msg.get("parts", []):
                if isinstance(p, dict) and "text" in p:
                    text += p["text"]
            
            # On masque le contexte mémoire injecté pour que l'app Android ne le voie pas
            text = re.sub(r'\[CONTEXTE MÉMOIRE PERTINENT.*?\]\n', '', text)
            
            formatted_history.append({
                "text": text,
                "isUser": msg.get("role") == "user"
            })
            
        return {"history": formatted_history}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/threads")
async def list_threads():
    """Liste tous les threads de discussion disponibles (basé sur les fichiers d'historique)."""
    try:
        from jarvis_engine import MEMORIES_DIR
        files = os.listdir(MEMORIES_DIR)
        # On cherche les fichiers history_<session>_<thread>.json
        threads = set()
        for f in files:
            if f.startswith("history_") and f.endswith(".json"):
                parts = f.replace("history_", "").replace(".json", "").split("_")
                if len(parts) >= 2:
                    threads.add(parts[1])
        
        # S'assurer que 'main' est toujours présent
        threads.add("main")
        return {"threads": list(threads)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/bridge")
async def get_bridge_notes():
    """Récupère les notes laissées par Jarvis pour le développeur."""
    from jarvis_engine import BRIDGE_FILE
    try:
        if not os.path.exists(BRIDGE_FILE):
            return {"notes": [], "count": 0}
        with open(BRIDGE_FILE, 'r', encoding='utf-8') as f:
            notes = json.load(f)
        return {"notes": notes, "count": len(notes)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/bridge/clear")
async def clear_bridge_notes():
    """Marque toutes les notes comme lues et vide le fichier pont."""
    from jarvis_engine import BRIDGE_FILE
    try:
        with open(BRIDGE_FILE, 'w', encoding='utf-8') as f:
            json.dump([], f)
        return {"status": "cleared"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    # On augmente le timeout à 120 secondes pour laisser le temps aux agents de réfléchir
    uvicorn.run(app, host="0.0.0.0", port=7860, timeout_keep_alive=120)
