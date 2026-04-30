import os
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from fastapi.middleware.cors import CORSMiddleware
from dotenv import load_dotenv
import sys
from pathlib import Path

load_dotenv()

# --- GESTION DU CHEMIN CREWAI ---
root_dir = Path(".")
crew_src = None
for path in root_dir.rglob("src"):
    if "crewai" in str(path).lower() or "jarvis" in str(path).lower():
        crew_src = str(path)
        break

if crew_src:
    sys.path.append(crew_src)
    print(f"✅ Dossier src trouvé : {crew_src}")
else:
    sys.path.append("src")
    print("⚠️ Dossier src non trouvé automatiquement")

try:
    from jarvis_ai_assistant.crew import JarvisAiAssistantCrew
except ImportError as e:
    print(f"❌ Erreur d'importation : {e}")

app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class ChatRequest(BaseModel):
    prompt: str

@app.get("/")
async def root():
    return {"status": "Online", "message": "Jarvis Server is running!"}

@app.post("/chat")
async def chat(request: ChatRequest):
    try:
        crew_instance = JarvisAiAssistantCrew().crew()
        
        # On fournit TOUTES les variables attendues par le YAML
        inputs = {
            'topic': request.prompt,
            'user_name': 'Antoine'
        }
        
        result = crew_instance.kickoff(inputs=inputs)
        return {"response": str(result)}
    
    except Exception as e:
        print(f"Erreur execution CrewAI : {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=7860)
