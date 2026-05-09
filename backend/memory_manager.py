import psycopg2
import json
import os
from contextlib import contextmanager

class MemoryManager:
    def __init__(self, db_path=None):
        # db_path est ignoré, on utilise SUPABASE_URI
        self._init_db()

    @contextmanager
    def get_conn(self):
        uri = os.getenv("SUPABASE_URI")
        if not uri:
            raise ValueError("SUPABASE_URI non configurée dans .env")
        conn = psycopg2.connect(uri)
        try:
            yield conn
        finally:
            conn.close()

    def _init_db(self):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS user_facts (
                        user_id TEXT,
                        fact TEXT,
                        embedding TEXT,
                        timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """)
                # PostgreSQL supporte IF NOT EXISTS pour ajouter une colonne !
                cursor.execute("ALTER TABLE user_facts ADD COLUMN IF NOT EXISTS embedding TEXT")
                
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_history (
                        user_id TEXT,
                        thread_id TEXT DEFAULT 'main',
                        role TEXT,
                        content TEXT,
                        timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """)
                cursor.execute("ALTER TABLE conversation_history ADD COLUMN IF NOT EXISTS thread_id TEXT DEFAULT 'main'")

                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS user_preferences (
                        user_id TEXT,
                        preference_key TEXT,
                        preference_value TEXT,
                        timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE(user_id, preference_key)
                    )
                """)

                # Nouvelle table pour le bridge (remplace jarvis_bridge.json)
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS bridge_notes (
                        id SERIAL PRIMARY KEY,
                        title TEXT,
                        message TEXT,
                        category TEXT DEFAULT 'INFO',
                        timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """)

                # Table pour les rappels proactifs (Smart Reminders)
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS scheduled_notifications (
                        id SERIAL PRIMARY KEY,
                        user_id TEXT,
                        title TEXT,
                        message TEXT,
                        scheduled_time TIMESTAMP,
                        sent BOOLEAN DEFAULT FALSE,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """)
            conn.commit()

    def save_fact(self, user_id, fact):
        embedding_str = "[]"
        try:
            from google import genai
            api_key = os.getenv("GEMINI_API_KEY")
            if api_key:
                client = genai.Client(api_key=api_key)
                response = client.models.embed_content(
                    model='gemini-embedding-2',
                    contents=fact,
                )
                embedding_str = json.dumps(response.embeddings[0].values)
        except Exception as e:
            print(f"Erreur lors de l'embedding du fait : {e}")

        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO user_facts (user_id, fact, embedding) VALUES (%s, %s, %s)", 
                    (user_id, fact, embedding_str)
                )
            conn.commit()

    def get_relevant_facts(self, user_id, current_query=None, top_k=5):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT fact, embedding FROM user_facts WHERE user_id = %s", (user_id,))
                rows = cursor.fetchall()

        if not rows:
            return []

        if not current_query:
            return [row[0] for row in rows[-top_k:]]

        try:
            import math
            from google import genai
            api_key = os.getenv("GEMINI_API_KEY")
            if not api_key:
                return [row[0] for row in rows[-top_k:]]

            client = genai.Client(api_key=api_key)
            response = client.models.embed_content(
                model='gemini-embedding-2',
                contents=current_query,
            )
            query_emb = response.embeddings[0].values

            def cosine_sim(v1, v2):
                dot = sum(x * y for x, y in zip(v1, v2))
                n1 = math.sqrt(sum(x * x for x in v1))
                n2 = math.sqrt(sum(x * x for x in v2))
                return dot / (n1 * n2) if n1 and n2 else 0.0

            scored_facts = []
            for fact, emb_str in rows:
                if emb_str and emb_str != "[]":
                    try:
                        emb = json.loads(emb_str)
                        score = cosine_sim(query_emb, emb)
                        scored_facts.append((score, fact))
                        continue
                    except:
                        pass
                scored_facts.append((0.0, fact))

            scored_facts.sort(key=lambda x: x[0], reverse=True)
            return [fact for score, fact in scored_facts[:top_k]]
        except Exception as e:
            print(f"Erreur recherche sémantique : {e}")
            return [row[0] for row in rows[-top_k:]]

    def get_all_facts(self, user_id):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT fact, timestamp FROM user_facts WHERE user_id = %s ORDER BY timestamp DESC", (user_id,))
                return [{"fact": row[0], "timestamp": row[1].isoformat() if row[1] else None} for row in cursor.fetchall()]

    def delete_fact(self, user_id, fact_text):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("DELETE FROM user_facts WHERE user_id = %s AND fact = %s", (user_id, fact_text))
            conn.commit()

    def add_to_history(self, user_id, role, content, thread_id="main"):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO conversation_history (user_id, role, content, thread_id) VALUES (%s, %s, %s, %s)",
                    (user_id, role, content, thread_id)
                )
            conn.commit()


    def get_conversation_history(self, user_id, thread_id="main", limit=20):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "SELECT role, content FROM conversation_history WHERE user_id = %s AND thread_id = %s ORDER BY timestamp DESC LIMIT %s", 
                    (user_id, thread_id, limit)
                )
                rows = cursor.fetchall()
        return [{"role": r[0], "content": r[1]} for r in reversed(rows)]

    def set_user_preference(self, user_id, preference_key, preference_value):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("""
                    INSERT INTO user_preferences (user_id, preference_key, preference_value) 
                    VALUES (%s, %s, %s)
                    ON CONFLICT (user_id, preference_key) 
                    DO UPDATE SET preference_value = EXCLUDED.preference_value, timestamp = CURRENT_TIMESTAMP
                """, (user_id, preference_key, preference_value))
            conn.commit()

    def get_user_preferences(self, user_id):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT preference_key, preference_value FROM user_preferences WHERE user_id = %s", (user_id,))
                rows = cursor.fetchall()
        return {r[0]: r[1] for r in rows}

    def schedule_notification(self, user_id, title, message, scheduled_time_iso):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("""
                    INSERT INTO scheduled_notifications (user_id, title, message, scheduled_time) 
                    VALUES (%s, %s, %s, %s)
                """, (user_id, title, message, scheduled_time_iso))
            conn.commit()

    def get_pending_notifications(self, user_id):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                # Récupère les notifs dont l'heure est passée et qui n'ont pas été envoyées
                cursor.execute("""
                    SELECT id, title, message FROM scheduled_notifications 
                    WHERE user_id = %s AND sent = FALSE AND scheduled_time <= CURRENT_TIMESTAMP
                """, (user_id,))
                rows = cursor.fetchall()
                
                # Marquer comme envoyées
                if rows:
                    ids = [r[0] for r in rows]
                    cursor.execute("UPDATE scheduled_notifications SET sent = TRUE WHERE id = ANY(%s)", (ids,))
            conn.commit()
        return [{"id": r[0], "title": r[1], "message": r[2]} for r in rows]
    def get_user_modes(self, user_id):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT id, name, instruction, icon, color FROM user_modes WHERE user_id = %s", (user_id,))
                rows = cursor.fetchall()
        return [{"id": r[0], "name": r[1], "instruction": r[2], "icon": r[3], "color": r[4]} for r in rows]

    def set_user_mode(self, user_id, name, instruction, icon='💎', color='#4285F4'):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("""
                    INSERT INTO user_modes (user_id, name, instruction, icon, color) 
                    VALUES (%s, %s, %s, %s, %s)
                    ON CONFLICT (user_id, name) 
                    DO UPDATE SET instruction = EXCLUDED.instruction, icon = EXCLUDED.icon, color = EXCLUDED.color
                """, (user_id, name, instruction, icon, color))
            conn.commit()

    def delete_user_mode(self, user_id, mode_name):
        with self.get_conn() as conn:
            with conn.cursor() as cursor:
                cursor.execute("DELETE FROM user_modes WHERE user_id = %s AND name = %s", (user_id, mode_name))
            conn.commit()

