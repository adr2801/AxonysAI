import sqlite3
from datetime import datetime

class NotificationManager:
    def __init__(self, db_path="notifications.db"):
        self.db_path = db_path
        self._init_db()

    def _init_db(self):
        """Initialise la base de données pour stocker les notifications."""
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    app_name TEXT,
                    title TEXT,
                    message TEXT,
                    timestamp DATETIME,
                    is_read INTEGER DEFAULT 0
                )
            """)
            conn.commit()

    def add_notification(self, app_name, title, message):
        """Ajoute une notification capturée à la base de données."""
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                "INSERT INTO notifications (app_name, title, message, timestamp) VALUES (?, ?, ?, ?)",
                (app_name, title, message, datetime.now())
            )
            conn.commit()

    def get_recent_notifications(self, hours=24):
        """Récupère uniquement les notifications NON LUES des dernières X heures."""
        with sqlite3.connect(self.db_path) as conn:
            conn.row_factory = sqlite3.Row
            cursor = conn.execute(
                "SELECT * FROM notifications WHERE timestamp >= datetime('now', ?) AND is_read = 0", 
                (f'-{hours} hours',)
            )
            return [dict(row) for row in cursor.fetchall()]

    def mark_all_as_read(self):
        """Marque toutes les notifications comme lues après un briefing."""
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("UPDATE notifications SET is_read = 1")
            conn.commit()

    def clear_read_notifications(self):
        """Nettoie les notifications marquées comme lues."""
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("DELETE FROM notifications WHERE is_read = 1")
            conn.commit()

# Instance globale pour l'application
notif_manager = NotificationManager()
