# Utiliser l'image officielle Python
FROM python:3.10-slim

# Créer un dossier pour l'app
WORKDIR /app

# Copier le fichier des dépendances et les installer
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copier tout le reste du code
COPY . .

# Lancer le serveur sur le port 7860
CMD ["python", "api_server.py"]
