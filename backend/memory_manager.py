import psycopg2
from psycopg2 import pool
import json
import os
from contextlib import contextmanager
from typing import List, Dict, Any, Optional

class MemoryManager:
    _instance = None
    _initialized = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super(MemoryManager, cls).__new__(cls)
        return cls._instance

    def __init__(self, db_path=None):
        if not self._initialized:
            self.pool = None
            self.has_vector = False
            self._init_pool()
            self._init_db()
            self._initialized = True

    def _init_pool(self):
        uri = os.getenv("SUPABASE_URI")
        if not uri:
            print("⚠️ SUPABASE_URI non configurée dans l'environnement.")
            return
        try:
            self.pool = pool.ThreadedConnectionPool(minconn=1, maxconn=10, dsn=uri)
            print("✅ Pool de connexions Supabase PostgreSQL initialisé (1-10 connexions).")
        except Exception as e:
            print(f"❌ Erreur initialisation pool Supabase : {e}")

    @contextmanager
    def get_conn(self):
        if not self.pool:
            self._init_pool()
        if not self.pool:
            raise ValueError("SUPABASE_URI non configurée ou inaccessible")
        conn = self.pool.getconn()
        try:
            yield conn
        finally:
            self.pool.putconn(conn)

    @staticmethod
    def normalize_user_id(user_id: Optional[str]) -> str:
        if not user_id:
            return "default_user"
        return user_id.strip().lower().replace(" ", "_")

    def _init_db(self):
        if not self.pool:
            return
        # 1. Activation pgvector
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("CREATE EXTENSION IF NOT EXISTS vector")
                conn.commit()
            self.has_vector = True
        except Exception as e:
            print(f"Note: pgvector non disponible ou déjà activé: {e}")
            self.has_vector = False

        # 2. Initialisation des tables
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    if self.has_vector:
                        cursor.execute("""
                            CREATE TABLE IF NOT EXISTS user_facts (
                                id SERIAL PRIMARY KEY,
                                user_id TEXT NOT NULL,
                                fact TEXT NOT NULL,
                                embedding vector(768),
                                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                            )
                        """)
                    else:
                        cursor.execute("""
                            CREATE TABLE IF NOT EXISTS user_facts (
                                id SERIAL PRIMARY KEY,
                                user_id TEXT NOT NULL,
                                fact TEXT NOT NULL,
                                embedding TEXT,
                                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                            )
                        """)
                    
                    cursor.execute("""
                        CREATE TABLE IF NOT EXISTS conversation_history (
                            id SERIAL PRIMARY KEY,
                            user_id TEXT NOT NULL,
                            thread_id TEXT DEFAULT 'main',
                            role TEXT NOT NULL,
                            content TEXT NOT NULL,
                            timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """)
                    cursor.execute("CREATE INDEX IF NOT EXISTS idx_history_user_thread ON conversation_history(user_id, thread_id)")

                    cursor.execute("""
                        CREATE TABLE IF NOT EXISTS user_preferences (
                            id SERIAL PRIMARY KEY,
                            user_id TEXT NOT NULL,
                            preference_key TEXT NOT NULL,
                            preference_value TEXT NOT NULL,
                            timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            UNIQUE(user_id, preference_key)
                        )
                    """)

                    cursor.execute("""
                        CREATE TABLE IF NOT EXISTS bridge_notes (
                            id SERIAL PRIMARY KEY,
                            title TEXT,
                            message TEXT,
                            category TEXT DEFAULT 'INFO',
                            timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """)

                    cursor.execute("""
                        CREATE TABLE IF NOT EXISTS scheduled_notifications (
                            id SERIAL PRIMARY KEY,
                            user_id TEXT NOT NULL,
                            title TEXT NOT NULL,
                            message TEXT NOT NULL,
                            scheduled_time TIMESTAMP NOT NULL,
                            sent BOOLEAN DEFAULT FALSE,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """)

                    cursor.execute("""
                        CREATE TABLE IF NOT EXISTS user_modes (
                            id SERIAL PRIMARY KEY,
                            user_id TEXT NOT NULL,
                            name TEXT NOT NULL,
                            instruction TEXT NOT NULL,
                            icon TEXT DEFAULT '💎',
                            color TEXT DEFAULT '#4285F4',
                            UNIQUE(user_id, name)
                        )
                    """)

                    cursor.execute("""
                        CREATE TABLE IF NOT EXISTS user_tasks (
                            id SERIAL PRIMARY KEY,
                            user_id TEXT NOT NULL,
                            name TEXT NOT NULL,
                            score REAL DEFAULT 0.0,
                            urgency REAL DEFAULT 5.0,
                            importance REAL DEFAULT 5.0,
                            duration REAL DEFAULT 5.0,
                            envy REAL DEFAULT 5.0,
                            energy REAL DEFAULT 5.0,
                            status TEXT DEFAULT 'pending',
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """)
                conn.commit()
        except Exception as e:
            print(f"⚠️ Erreur vérification tables Supabase : {e}")

    def _get_embedding(self, text: str) -> Optional[List[float]]:
        """Génère l'embedding 768 dimensions via Google GenAI (text-embedding-004)."""
        api_key = os.getenv("GEMINI_API_KEY")
        if not api_key:
            return None
        try:
            from google import genai
            client = genai.Client(api_key=api_key)
            # text-embedding-004 est le modèle officiel de référence (768 dimensions)
            response = client.models.embed_content(
                model='text-embedding-004',
                contents=text,
            )
            if response.embeddings and len(response.embeddings) > 0:
                return response.embeddings[0].values
        except Exception as e:
            print(f"⚠️ Erreur calcul embedding (text-embedding-004) : {e}")
        return None

    def save_fact(self, user_id: str, fact: str):
        if not fact or not fact.strip():
            return
        uid = self.normalize_user_id(user_id)
        cleaned_fact = fact.strip()
        embedding = self._get_embedding(cleaned_fact)

        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    # Vérifier si ce fait exact existe déjà pour cet utilisateur
                    cursor.execute(
                        "SELECT id FROM user_facts WHERE user_id = %s AND fact = %s LIMIT 1",
                        (uid, cleaned_fact)
                    )
                    if cursor.fetchone():
                        return
                    
                    if embedding:
                        cursor.execute(
                            "INSERT INTO user_facts (user_id, fact, embedding) VALUES (%s, %s, %s)", 
                            (uid, cleaned_fact, embedding)
                        )
                    else:
                        cursor.execute(
                            "INSERT INTO user_facts (user_id, fact) VALUES (%s, %s)", 
                            (uid, cleaned_fact)
                        )
                conn.commit()
                print(f"💾 Fait mémorisé pour [{uid}] : {cleaned_fact}")
        except Exception as e:
            print(f"❌ Erreur sauvegarde fait dans Supabase : {e}")

    def get_relevant_facts(self, user_id: str, current_query: Optional[str] = None, top_k: int = 5) -> List[str]:
        uid = self.normalize_user_id(user_id)
        if not current_query or not current_query.strip():
            try:
                with self.get_conn() as conn:
                    with conn.cursor() as cursor:
                        cursor.execute(
                            "SELECT fact FROM user_facts WHERE user_id = %s ORDER BY timestamp DESC LIMIT %s", 
                            (uid, top_k)
                        )
                        return [row[0] for row in cursor.fetchall()]
            except Exception as e:
                print(f"Erreur lecture faits récents : {e}")
                return []

        # Recherche sémantique vectorielle
        query_emb = self._get_embedding(current_query)
        if query_emb:
            try:
                with self.get_conn() as conn:
                    with conn.cursor() as cursor:
                        cursor.execute("""
                            SELECT fact 
                            FROM user_facts 
                            WHERE user_id = %s AND embedding IS NOT NULL
                            ORDER BY embedding <=> %s::vector
                            LIMIT %s
                        """, (uid, query_emb, top_k))
                        results = [row[0] for row in cursor.fetchall()]
                        if results:
                            return results
            except Exception as e:
                print(f"⚠️ Erreur recherche vectorielle pgvector (repli sur récents) : {e}")

        # Repli : faits les plus récents
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute(
                        "SELECT fact FROM user_facts WHERE user_id = %s ORDER BY timestamp DESC LIMIT %s", 
                        (uid, top_k)
                    )
                    return [row[0] for row in cursor.fetchall()]
        except Exception as e:
            print(f"Erreur fallback faits : {e}")
            return []

    def get_all_facts(self, user_id: str) -> List[Dict[str, Any]]:
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("SELECT fact, timestamp FROM user_facts WHERE user_id = %s ORDER BY timestamp DESC", (uid,))
                    return [{"fact": row[0], "timestamp": row[1].isoformat() if row[1] else None} for row in cursor.fetchall()]
        except Exception as e:
            print(f"Erreur get_all_facts : {e}")
            return []

    def delete_fact(self, user_id: str, fact_text: str):
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("DELETE FROM user_facts WHERE user_id = %s AND fact = %s", (uid, fact_text))
                conn.commit()
        except Exception as e:
            print(f"Erreur delete_fact : {e}")

    def add_to_history(self, user_id: str, role: str, content: str, thread_id: str = "main"):
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute(
                        "INSERT INTO conversation_history (user_id, role, content, thread_id) VALUES (%s, %s, %s, %s)",
                        (uid, role, content, thread_id)
                    )
                conn.commit()
        except Exception as e:
            print(f"Erreur add_to_history : {e}")

    def get_conversation_history(self, user_id: str, thread_id: str = "main", limit: int = 20) -> List[Dict[str, Any]]:
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute(
                        "SELECT role, content FROM conversation_history WHERE user_id = %s AND thread_id = %s ORDER BY timestamp DESC LIMIT %s", 
                        (uid, thread_id, limit)
                    )
                    rows = cursor.fetchall()
            return [{"role": r[0], "content": r[1]} for r in reversed(rows)]
        except Exception as e:
            print(f"Erreur get_conversation_history : {e}")
            return []

    def delete_message(self, user_id: str, thread_id: str, content: str):
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute(
                        "DELETE FROM conversation_history WHERE user_id = %s AND thread_id = %s AND content = %s",
                        (uid, thread_id, content)
                    )
                conn.commit()
        except Exception as e:
            print(f"Erreur delete_message : {e}")

    def clear_thread(self, user_id: str, thread_id: str):
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute(
                        "DELETE FROM conversation_history WHERE user_id = %s AND thread_id = %s",
                        (uid, thread_id)
                    )
                conn.commit()
        except Exception as e:
            print(f"Erreur clear_thread : {e}")

    def set_user_preference(self, user_id: str, preference_key: str, preference_value: str):
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("""
                        INSERT INTO user_preferences (user_id, preference_key, preference_value) 
                        VALUES (%s, %s, %s)
                        ON CONFLICT (user_id, preference_key) 
                        DO UPDATE SET preference_value = EXCLUDED.preference_value, timestamp = CURRENT_TIMESTAMP
                    """, (uid, preference_key, preference_value))
                conn.commit()
        except Exception as e:
            print(f"Erreur set_user_preference : {e}")

    def get_user_preferences(self, user_id: str) -> Dict[str, str]:
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("SELECT preference_key, preference_value FROM user_preferences WHERE user_id = %s", (uid,))
                    rows = cursor.fetchall()
            return {r[0]: r[1] for r in rows}
        except Exception as e:
            print(f"Erreur get_user_preferences : {e}")
            return {}

    def schedule_notification(self, user_id: str, title: str, message: str, scheduled_time_iso: str):
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("""
                        INSERT INTO scheduled_notifications (user_id, title, message, scheduled_time) 
                        VALUES (%s, %s, %s, %s)
                    """, (uid, title, message, scheduled_time_iso))
                conn.commit()
        except Exception as e:
            print(f"Erreur schedule_notification : {e}")

    def get_pending_notifications(self, user_id: str) -> List[Dict[str, Any]]:
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("""
                        SELECT id, title, message FROM scheduled_notifications 
                        WHERE user_id = %s AND sent = FALSE AND scheduled_time <= CURRENT_TIMESTAMP
                    """, (uid,))
                    rows = cursor.fetchall()
                    
                    if rows:
                        ids = [r[0] for r in rows]
                        cursor.execute("UPDATE scheduled_notifications SET sent = TRUE WHERE id = ANY(%s)", (ids,))
                conn.commit()
            return [{"id": r[0], "title": r[1], "message": r[2]} for r in rows]
        except Exception as e:
            print(f"Erreur get_pending_notifications : {e}")
            return []

    def get_user_modes(self, user_id: str) -> List[Dict[str, Any]]:
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("SELECT id, name, instruction, icon, color FROM user_modes WHERE user_id = %s", (uid,))
                    rows = cursor.fetchall()
            return [{"id": r[0], "name": r[1], "instruction": r[2], "icon": r[3], "color": r[4]} for r in rows]
        except Exception as e:
            print(f"Erreur get_user_modes : {e}")
            return []

    def set_user_mode(self, user_id: str, name: str, instruction: str, icon: str = '💎', color: str = '#4285F4'):
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("""
                        INSERT INTO user_modes (user_id, name, instruction, icon, color) 
                        VALUES (%s, %s, %s, %s, %s)
                        ON CONFLICT (user_id, name) 
                        DO UPDATE SET instruction = EXCLUDED.instruction, icon = EXCLUDED.icon, color = EXCLUDED.color
                    """, (uid, name, instruction, icon, color))
                conn.commit()
        except Exception as e:
            print(f"Erreur set_user_mode : {e}")

    def delete_user_mode(self, user_id: str, mode_name: str):
        uid = self.normalize_user_id(user_id)
        try:
            with self.get_conn() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("DELETE FROM user_modes WHERE user_id = %s AND name = %s", (uid, mode_name))
                conn.commit()
        except Exception as e:
            print(f"Erreur delete_user_mode : {e}")

