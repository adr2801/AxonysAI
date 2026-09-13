import os
import sys
from pathlib import Path
from dotenv import load_dotenv

# Chargement de l'environnement
BASE_DIR = Path(__file__).parent.absolute()
load_dotenv(dotenv_path=BASE_DIR / ".env")

import gradio as gr
import uvicorn
from api_server import app as fastapi_app, jarvis, memory_manager

# Support ZeroGPU conditionnel (fonctionne sur HF Spaces avec GPU et en local sur CPU)
try:
    import spaces
    has_spaces = True
except ImportError:
    has_spaces = False
    # Mock spaces.GPU si exécuté hors de Hugging Face Spaces
    class spaces:
        @staticmethod
        def GPU(func=None, duration=60):
            if func is None:
                return lambda f: f
            return func

# Fonction d'inférence Gradio (compatible ZeroGPU)
@spaces.GPU(duration=60)
async def predict_gradio(message, history, user_name, mode):
    """Fonction de chat Gradio avec appel au moteur Jarvis."""
    if not message.strip():
        return
    
    response_chunks = []
    async for chunk in jarvis.process_query_stream(
        prompt=message,
        user_id="gradio_user",
        user_name=user_name or "Antoine",
        mode=mode if mode != "Automatique" else None,
        save_to_history=True
    ):
        if isinstance(chunk, str):
            response_chunks.append(chunk)
            yield "".join(response_chunks)


def get_memories_text():
    try:
        facts = memory_manager.get_all_facts("gradio_user")
        if not facts:
            return "Aucun souvenir enregistré pour le moment."
        return "\n".join([f"• [{f.get('timestamp', 'N/A')}] {f.get('fact')}" for f in facts])
    except Exception as e:
        return f"Erreur de lecture : {e}"


def get_bridge_text():
    try:
        with memory_manager.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT id, title, message, category, timestamp FROM bridge_notes ORDER BY timestamp DESC LIMIT 20")
                rows = cursor.fetchall()
        if not rows:
            return "Aucune note technique dans le bridge."
        return "\n\n".join([f"[{r[4]}] ({r[3]}) {r[1]} :\n{r[2]}" for r in rows])
    except Exception as e:
        return f"Erreur de lecture bridge : {e}"


# Construction de l'interface Gradio
with gr.Blocks(title="Axonys AI - Jarvis Core") as demo:
    gr.Markdown(
        """
        # 🧠 Axonys AI (Jarvis)
        *Interface d'administration et de test pour le moteur cognitif Axonys.*
        
        > 💡 **Remarque** : L'API FastAPI reste active et accessible simultanément pour l'application Android native.
        """
    )
    
    with gr.Tab("💬 Discussion avec Jarvis"):
        with gr.Row():
            user_name_input = gr.Textbox(value="Antoine", label="Nom d'utilisateur", scale=1)
            mode_selector = gr.Dropdown(
                choices=["Automatique", "Coder", "Analyst", "Creative", "Concierge"],
                value="Automatique",
                label="Mode cognitif",
                scale=1
            )
        
        chatbot = gr.ChatInterface(
            fn=predict_gradio,
            additional_inputs=[user_name_input, mode_selector]
        )
    
    with gr.Tab("💾 Mémoire Vectorielle (Supabase)"):
        gr.Markdown("### Souvenirs et faits mémorisés par l'agent")
        memories_display = gr.Textbox(value=get_memories_text, label="Faits enregistrés", lines=12, interactive=False)
        refresh_mem_btn = gr.Button("🔄 Actualiser la mémoire")
        refresh_mem_btn.click(fn=get_memories_text, outputs=memories_display)
    
    with gr.Tab("🌉 Bridge Développeur"):
        gr.Markdown("### Notes et alertes techniques remontées par Jarvis")
        bridge_display = gr.Textbox(value=get_bridge_text, label="Notes Bridge", lines=12, interactive=False)
        refresh_bridge_btn = gr.Button("🔄 Actualiser le Bridge")
        refresh_bridge_btn.click(fn=get_bridge_text, outputs=bridge_display)

# Intégration transparente des routes de l'API mobile (FastAPI) dans l'application Gradio
demo.app.include_router(fastapi_app.router)
demo.queue()

if __name__ == "__main__":
    # Lancement du serveur officiel Gradio (maintenu actif 24/7 sur Hugging Face)
    demo.launch(server_name="0.0.0.0", server_port=7860, show_error=True)




