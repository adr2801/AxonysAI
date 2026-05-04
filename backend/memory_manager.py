import sqlite3
import json

class MemoryManager:
    def __init__(self, db_path="jarvis_memory.db"):
        self.db_path = db_path
        self._init_db()

    def _init_db(self):
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS user_facts (
                    user_id TEXT,
                    fact TEXT,
                    embedding TEXT,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """)
            # Migration pour ajouter la colonne embedding si la table existait déjà
            try:
                conn.execute("ALTER TABLE user_facts ADD COLUMN embedding TEXT")
            except sqlite3.OperationalError:
                pass # La colonne existe déjà
            conn.execute("""
                CREATE TABLE IF NOT EXISTS conversation_history (
                    user_id TEXT,
                    role TEXT,
                    content TEXT,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """)

    def save_fact(self, user_id, fact):
        embedding_str = "[]"
        try:
            import os
            from google import genai
            api_key = os.getenv("GEMINI_API_KEY")
            if api_key:
                client = genai.Client(api_key=api_key)
                response = client.models.embed_content(
                    model='text-embedding-004',
                    contents=fact,
                )
                embedding_str = json.dumps(response.embeddings[0].values)
        except Exception as e:
            print(f"Erreur lors de l'embedding du fait : {e}")

        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                "INSERT INTO user_facts (user_id, fact, embedding) VALUES (?, ?, ?)", 
                (user_id, fact, embedding_str)
            )

    def get_relevant_facts(self, user_id, current_query=None, top_k=5):
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.execute("SELECT fact, embedding FROM user_facts WHERE user_id = ?", (user_id,))
            rows = cursor.fetchall()

        if not rows:
            return []

        if not current_query:
            # Sans requête spécifique, on renvoie les derniers faits
            return [row[0] for row in rows[-top_k:]]

        try:
            import os
            import math
            from google import genai
            api_key = os.getenv("GEMINI_API_KEY")
            if not api_key:
                return [row[0] for row in rows[-top_k:]]

            client = genai.Client(api_key=api_key)
            response = client.models.embed_content(
                model='text-embedding-004',
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
            print(f"Erreur lors de la recherche sémantique : {e}")
            return [row[0] for row in rows[-top_k:]]

    def add_to_history(self, user_id, role, content):
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                "INSERT INTO conversation_history (user_id, role, content) VALUES (?, ?, ?)",
                (user_id, role, content)
            )
