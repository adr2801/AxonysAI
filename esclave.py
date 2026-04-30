from fastapi import FastAPI
import subprocess

app = FastAPI()

@app.post("/execute")
def execute_command(command: str):
    try:
        # Le PC exécute la commande reçue depuis la VM dans son propre terminal
        result = subprocess.run(command, shell=True, capture_output=True, text=True)
        return {"status": "success", "output": result.stdout, "error": result.stderr}
    except Exception as e:
        return {"status": "failed", "error": str(e)}