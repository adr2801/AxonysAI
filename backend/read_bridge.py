import os
import psycopg2
from dotenv import load_dotenv

load_dotenv('.env')

uri = os.getenv('SUPABASE_URI')
if not uri:
    print("SUPABASE_URI n'est pas trouvée dans .env")
    exit(1)

try:
    conn = psycopg2.connect(uri)
    cur = conn.cursor()
    cur.execute('SELECT title, message, category, timestamp FROM bridge_notes ORDER BY timestamp DESC')
    rows = cur.fetchall()
    if not rows:
        print("Aucune note de Jarvis pour le moment.")
    else:
        print("Notes de Jarvis:")
        for r in rows:
            print(f"\n--- [{r[2]}] {r[0]} ({r[3]}) ---")
            print(r[1])
            print("-" * 40)
except Exception as e:
    print(f"Erreur de connexion : {e}")
