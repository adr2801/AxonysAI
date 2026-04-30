import os
import json
import requests
from google.oauth2.credentials import Credentials

# Les scopes nécessaires pour Gmail, Calendar et Drive
SCOPES = [
    'https://www.googleapis.com/auth/gmail.modify',
    'https://www.googleapis.com/auth/calendar',
    'https://www.googleapis.com/auth/documents',
    'https://www.googleapis.com/auth/spreadsheets',
    'https://www.googleapis.com/auth/drive.file'
]

# L'URL de redirection doit correspondre exactement à celle configurée dans la console Google Cloud
REDIRECT_URI = "com.adr28.cortexia:/oauth2redirect"

def get_authorization_url():
    """Génère l'URL d'autorisation Google manuellement sans utiliser oauthlib."""
    client_secrets_file = 'client_secret.json'
    if not os.path.exists(client_secrets_file):
        raise FileNotFoundError("Le fichier 'client_secret.json' est manquant.")
    
    with open(client_secrets_file, 'r') as f:
        client_config = json.load(f)
    
    client_id = client_config['web']['client_id'] if 'web' in client_config else client_config['installed']['client_id']
    
    import urllib.parse
    params = {
        "client_id": client_id,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": " ".join(SCOPES),
        "access_type": "offline",
        "prompt": "consent"
    }
    
    auth_url = f"https://accounts.google.com/o/oauth2/v2/auth?{urllib.parse.urlencode(params)}"
    return auth_url

def exchange_code_for_token(code):
    """Échange le code pour un token via une requête HTTP simple (SANS wsgiref)."""
    client_secrets_file = 'client_secret.json'
    with open(client_secrets_file, 'r') as f:
        client_config = json.load(f)
    
    client_id = client_config['web']['client_id'] if 'web' in client_config else client_config['installed']['client_id']
    client_secret = client_config['web']['client_secret'] if 'web' in client_config else client_config['installed']['client_secret']

    token_url = "https://oauth2.googleapis.com/token"
    data = {
        "code": code,
        "client_id": client_id,
        "client_secret": client_secret,
        "redirect_uri": REDIRECT_URI,
        "grant_type": "authorization_code",
    }

    response = requests.post(token_url, data=data)
    if response.status_code != 200:
        raise Exception(f"Erreur Google Token: {response.text}")
    
    token_data = response.json()
    
    with open('token.json', 'w') as token_file:
        json.dump(token_data, token_file)
        
    return token_data

def is_google_connected():
    return os.path.exists('token.json')

def disconnect_google():
    if os.path.exists('token.json'):
        os.remove('token.json')
    return True
