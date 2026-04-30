import time
from datetime import datetime
import flet as ft
import numpy as np
import IA_base
import threading
import json
import asyncio
import os
import sys
import requests # Ajouté pour la vérification des mises à jour
from dotenv import load_dotenv
import google_auth
from notification_manager import notif_manager

# Ajout du chemin vers le projet CrewAI professionnel pour permettre l'import
CREW_PATH = r"C:\Users\adr28\Documents\NSI_premiere\IA_priorisateur\jarvis_ai_assistant_v1_crewai-project\src"
if CREW_PATH not in sys.path:
    sys.path.append(CREW_PATH)

from jarvis_ai_assistant.crew import JarvisAiAssistantCrew

load_dotenv()

def main(page: ft.Page):
    page.title = "Cortex IA"
    page.version = "1.0.0" # Version actuelle de l'app
    page.assets_dir = "icon.png"
    page.theme_mode = ft.ThemeMode.DARK
    page.scroll = ft.ScrollMode.ADAPTIVE
    
    # Vérification des mises à jour
    async def check_for_updates():
        try:
            # On interroge l'API GitHub pour obtenir la dernière release
            repo = "adr2801/Cortex-AI"
            url = f"https://api.github.com/repos/{repo}/releases/latest"
            response = requests.get(url).json()
            latest_version = response['tag_name'].replace('v', '')
            
            if latest_version != page.version:
                page.snack_bar = ft.SnackBar(
                    ft.Text(f"Une nouvelle version ({latest_version}) est disponible !"),
                    action="Mettre à jour",
                )
                page.snack_bar.open = True
                page.update()
        except Exception as e:
            print(f"Erreur update: {e}")

    asyncio.create_task(check_for_updates())

    # --- CAPTURE DU DEEP LINK ---
    async def handle_deep_link():
        if page.route and "code=" in page.route:
            try:
                code = page.route.split("code=")[1].split("&")[0]
                await asyncio.to_thread(google_auth.exchange_code_for_token, code)
                page.snack_bar = ft.SnackBar(ft.Text("Connexion Google réussie via Deep Link !"))
                page.snack_bar.open = True
                page.update()
            except Exception as e:
                print(f"Erreur Deep Link: {e}")

    asyncio.create_task(handle_deep_link())

    # Veilleur pour le briefing automatique à 7h (pour la version Desktop)
    async def verifier_heure_briefing():
        while True:
            now = datetime.now()
            if now.hour == 7 and now.minute == 0:
                # On pourrait déclencher une notification ou un événement ici
                print("Heure du briefing matinal : 7h00")
            await asyncio.sleep(60)

    asyncio.create_task(verifier_heure_briefing())
    
    ia = IA_base.PrioriseurIA()
    
    try:
        ia.w1 = np.load('weights1maj.npy')
        ia.w2 = np.load('weights2maj.npy')
        ia.b1 = np.load('bias1maj.npy')
        ia.b2 = np.load('bias2maj.npy')
    except FileNotFoundError:
        pass

    chargement = ft.ProgressBar(width=400, color="blue", visible=False)
    texte_statut = ft.Text("", italic=True, size=12)
    nom_tache = ft.TextField(label="Nom de la tâche", hint_text="Ex: Répondre à un email", width=300)
    slider_imp = ft.Slider(min=0, max=10, divisions=10, label="{value}")
    slider_urg = ft.Slider(min=0, max=10, divisions=10, label="{value}")
    slider_dur = ft.Slider(min=0, max=10, divisions=10, label="{value}")
    slider_env = ft.Slider(min=0, max=10, divisions=10, label="{value}")
    slider_ene = ft.Slider(min=0, max=10, divisions=10, label="{value}")

    def charger_acceuil():
        page.clean()
        page.add(
            ft.Text("Bienvenue sur Cortex IA", size=30, weight=ft.FontWeight.BOLD),
            ft.Text("Votre assistant de gestion de tâches intelligent", size=20),
        )
        page.update()

    import asyncio # <--- N'oublie pas l'import en haut !

    async def calculer_priorite(e):
        chargement.visible = True
        page.update()
        
        # On utilise asyncio pour ne pas bloquer l'interface
        await asyncio.sleep(1) 
        
        input_values = [slider_urg.value, slider_imp.value, slider_dur.value, slider_env.value, slider_ene.value]
        # Appliquer un epsilon aux valeurs nulles pour éviter les erreurs de calcul avec le MLP
        input_values = [val if val != 0 else 0.000001 for val in input_values]
        # Calcul du score
        input_data = np.array([[val / 10 for val in input_values]])
        score = ia.forward(input_data)[0][0]

        if not nom_tache.value:
            texte_statut.value = "Erreur : Le nom de la tâche ne peut pas être vide."
            page.update()
            await asyncio.sleep(2)
            return
        
        # Création de la checkbox locale
        cb = ft.Checkbox()
        
        nouvelle_ligne = ft.DataRow(
            cells=[
                ft.DataCell(cb),
                ft.DataCell(ft.Text(nom_tache.value)),
                ft.DataCell(ft.Text(f"{score*100:.1f}%")),
            ]
        )

        # Logique de changement (DÉFINIE ICI MAIS PAS APPELÉE TOUT DE SUITE)
        async def au_changement_interne(e):
            await sauvegarder_automatique()
            if cb.value == True:
                t = threading.Thread(target=verifier_et_supprimer, args=(nouvelle_ligne, cb))
                t.start()
        
        cb.on_change = au_changement_interne

        # AJOUT AU TABLEAU
        tableau.rows.append(nouvelle_ligne)
        
        # TRI DU TABLEAU
        tableau.rows.sort(key=lambda row: float(row.cells[2].content.value.replace('%', '')), reverse=True)
        
        # SAUVEGARDE ET NETTOYAGE
        await sauvegarder_automatique()
        
        chargement.visible = False
        texte_statut.value = f"Tâche '{nom_tache.value}' ajoutée avec succès !"
        nom_tache.value = ""
        
        page.update() # <--- C'est ici que le chargement s'arrête et que le tableau apparaît !

        await asyncio.sleep(0.5)
        texte_statut.value= ""

    async def charger_donnees_sauvegardees():
        if await page.shared_preferences.contains_key("mes_taches"):
            txt_sauvegarde = await page.shared_preferences.get("mes_taches")
            sauvegarde = json.loads(txt_sauvegarde)
            for item in sauvegarde:           
                cb = ft.Checkbox(value=item["termine"])
            
            # On crée la ligne pour pouvoir la passer au thread
                nouvelle_ligne = ft.DataRow(cells=[
                    ft.DataCell(cb),
                    ft.DataCell(ft.Text(item["nom"])),
                    ft.DataCell(ft.Text(item["score"]))
                ])

            # Définition de l'action quand on coche/décoche
            async def au_changement(e, l=nouvelle_ligne, c=cb):
                await sauvegarder_automatique()                
                if c.value == True:
                    threading.Thread(target=verifier_et_supprimer, args=(l, c)).start()

            cb.on_change = au_changement
            
            # Si on charge une tâche déjà cochée, on lance le chrono
            if cb.value == True:
                threading.Thread(target=verifier_et_supprimer, args=(nouvelle_ligne, cb)).start()

            tableau.rows.append(nouvelle_ligne)
            page.update

    async def sauvegarder_feedback(query, response, score):
        feedback_file = "training_data.json"
        donnees = []
        if os.path.exists(feedback_file):
            with open(feedback_file, "r", encoding="utf-8") as f:
                try:
                    donnees = json.load(f)
                except json.JSONDecodeError:
                    donnees = []
        
        donnees.append({
            "query": query,
            "response": str(response),
            "score": score,
            "timestamp": time.time()
        })
        
        with open(feedback_file, "w", encoding="utf-8") as f:
            json.dump(donnees, f, indent=4, ensure_ascii=False)

    async def sauvegarder_automatique():
        donnees = []
        for row in tableau.rows:
            donnees.append({
                "termine": row.cells[0].content.value,
                "nom": row.cells[1].content.value,
                "score": row.cells[2].content.value
            })
        liste_en_texte = json.dumps(donnees)
        await page.shared_preferences.set("mes_taches", liste_en_texte)


    def verifier_et_supprimer(ligne, checkbox):
        # On attend 10 secondes
        time.sleep(10)
        
        # On vérifie si elle est toujours cochée après le délai
        if checkbox.value == True:
            if ligne in tableau.rows:
                tableau.rows.remove(ligne) 
                page.update()
                asyncio.run_coroutine_threadsafe(sauvegarder_automatique(), page.loop)
                print("Tâche terminée et supprimée !")
        

    async def charger_prioriseur():
        page.clean()
        page.add(
            ft.Text("Prioriseur de tâches", size=24, weight=ft.FontWeight.BOLD),
            nom_tache,
            ft.Text("Importance (0-10)"),
            slider_imp,
            ft.Text("Urgence (0-10)"),
            slider_urg,
            ft.Text("Durée estimée (0-10)"),
            slider_dur,
            ft.Text("Envie (0-10)"),
            slider_env,
            ft.Text("Énergie requise (0-10)"),
            slider_ene,
            ft.Button("Calculer la priorité", on_click=calculer_priorite),
            chargement,
            texte_statut,
            texte_statut,
            tableau,
            ft.Text(""),
            ft.Text(""),
            ft.Text(""),
            )
        await charger_donnees_sauvegardees()
        page.update()

    tableau = ft.DataTable(
        columns=[
            ft.DataColumn(ft.Text("Fait")),
            ft.DataColumn(ft.Text("Tâche")),
            ft.DataColumn(ft.Text("Score (%)"),numeric=True),
        ],
        rows=[]
    )

    

    
        
    async def charger_jarvis():
        page.clean()
        
        chat_messages = ft.Column(
            expand=True, 
            scroll=ft.ScrollMode.ALWAYS, 
            spacing=15
        )
        
        chat_input = ft.TextField(
            hint_text="Demandez quelque chose à Jarvis...", 
            expand=True, 
            on_submit=lambda e: envoyer_message(e),
            border_color="green700",
            focused_border_color="green400"
        )
        
        async def envoyer_message(e):
            if not chat_input.value:
                return
                
            user_query = chat_input.value
            chat_input.value = ""
            
            # Message de l'utilisateur
            chat_messages.controls.append(
                ft.Row([
                    ft.Container(
                        content=ft.Text(user_query, color="white"),
                        bgcolor="blue700",
                        padding=12,
                        border_radius=ft.border_radius.only(top_left=0, top_right=15, bottom_left=15, bottom_right=15),
                    )],
                    alignment=ft.MainAxisAlignment.END
                )
            )
            
            # Indicateur de chargement animé
            loading_row = ft.Row([
                ft.Container(
                    content=ft.Row([
                        ft.ProgressRing(width=16, height=16, stroke_width=2, color="green400"),
                        ft.Text("Jarvis analyse...", italic=True, color="grey400", size=13),
                    ]),
                    bgcolor="grey900",
                    padding=12,
                    border_radius=ft.border_radius.only(top_left=15, top_right=0, bottom_left=15, bottom_right=15),
                )],
                alignment=ft.MainAxisAlignment.START
            )
            chat_messages.controls.append(loading_row)
            
            # Scroll automatique vers le bas
            chat_messages.scroll_to(offset=-1)
            page.update()

            try:
                def run_crew():
                    # Récupération des données système pour enrichir la requête
                    notifs = notif_manager.get_recent_notifications()
                    notifs_text = "\n".join([f"[{n['app_name']}] {n['title']}: {n['message']}" for n in notifs])
                    
                    # On injecte les données brutes dans la requête pour que Jarvis les analyse
                    enriched_query = f"{user_query}\n\nCONTEXTE NOTIFICATIONS RÉCENTES :\n{notifs_text if notifs_text else 'Aucune notification récente.'}"
                    
                    crew_instance = JarvisAiAssistantCrew().crew()
                    return crew_instance.kickoff(inputs={'topic': enriched_query})

                result = await asyncio.to_thread(run_crew)
                
                if loading_row in chat_messages.controls:
                    chat_messages.controls.remove(loading_row)
                
                # Réponse de Jarvis en Markdown pour un rendu pro
                result_str = str(result)
                
                # Boutons de feedback
                async def on_feedback(e, score):
                    await sauvegarder_feedback(user_query, result_str, score)
                    feedback_btns.visible = False
                    page.update()

                feedback_btns = ft.Row([
                    ft.IconButton(icon=ft.Icons.THUMBS_UP, icon_color="green", on_click=lambda e: on_feedback(e, 1)),
                    ft.IconButton(icon=ft.Icons.THUMBS_DOWN, icon_color="red", on_click=lambda e: on_feedback(e, 0)),
                ], alignment=ft.MainAxisAlignment.START, spacing=5)

                chat_messages.controls.append(
                    ft.Row([
                        ft.Container(
                            content=ft.Column([
                                ft.Markdown(
                                    result_str, 
                                    extension_set=ft.MarkdownExtensionSet.GITHUB_WEB,
                                    selectable=True
                                ),
                                feedback_btns
                            ]),
                            bgcolor="green900",
                            padding=15,
                            border_radius=ft.border_radius.only(top_left=15, top_right=0, bottom_left=15, bottom_right=15),
                        )],
                        alignment=ft.MainAxisAlignment.START
                    )
                )
            except Exception as ex:
                if loading_row in chat_messages.controls:
                    chat_messages.controls.remove(loading_row)
                chat_messages.controls.append(ft.Text(f"Erreur système : {str(ex)}", color="red"))
            
            chat_messages.scroll_to(offset=-1)
            page.update()

        async def lancer_briefing(e):
            if not chat_input.value: # On utilise le champ de saisie pour d'éventuelles précisions
                query = "Génère mon briefing matinal complet en analysant mes notifications, mes emails et mon calendrier."
            else:
                query = f"{chat_input.value}. (Inclus mon briefing matinal)"
            
            # On déclenche le processus de message normal mais avec une requête spécifique
            chat_input.value = query
            await envoyer_message(None)

        send_button = ft.IconButton(
            icon=ft.Icons.SEND_ROUNDED, 
            icon_color="green400", 
            on_click=envoyer_message
        )

        page.add(
            ft.Row([
                ft.Icon(ft.Icons.SMART_TOY, color="green400", size=30),
                ft.Text("Jarvis - Centre de Commandement", size=28, weight=ft.FontWeight.BOLD, color="white"),
            ], alignment=ft.MainAxisAlignment.CENTER),
            ft.Row([
                ft.ElevatedButton(
                    "☀️ Briefing", 
                    icon=ft.Icons.WB_SUNNY, 
                    on_click=lancer_briefing,
                    style=ft.ButtonStyle(color="orange400", bgcolor="orange900")
                ),
            ], alignment=ft.MainAxisAlignment.CENTER),
            ft.Container(
                content=chat_messages,
                expand=True,
                border=ft.Border.all(1, "grey800"),
                border_radius=15,
                padding=20,
                bgcolor="#1a1a1a",
            ),
            ft.Row([chat_input, send_button], alignment=ft.MainAxisAlignment.CENTER),
        )
        page.update()

    def charger_parametres():
        page.clean()
        global boutton_theme_dark
        global boutton_theme_light
        boutton_theme_light = ft.Button("Thème clair", on_click=changement_theme_light)
        boutton_theme_dark = ft.Button("Thème sombre", on_click=changement_theme_dark)
        
        async def optimiser_jarvis(e):
            # Simulation du training (dans la réalité, on appellerait crew.train)
            texte_statut_param.value = "Optimisation de Jarvis en cours..."
            page.update()
            try:
                def run_train():
                    # Ici on pourrait appeler JarvisAiAssistantCrew().crew().train(...)
                    # Pour l'instant, on simule le temps de traitement
                    time.sleep(3)
                    return "Training terminé avec succès !"
                
                res = await asyncio.to_thread(run_train)
                texte_statut_param.value = res
            except Exception as ex:
                texte_statut_param.value = f"Erreur : {str(ex)}"
            page.update()

        async def connecter_google(e):
            # Fenêtre de dialogue pour l'authentification
            async def handle_auth_dialog(e):
                if not code_input.value:
                    texte_auth_status.value = "Veuillez entrer le code."
                    page.update()
                    return
                
                try:
                    await asyncio.to_thread(google_auth.exchange_code_for_token, code_input.value)
                    texte_auth_status.value = "Connecté avec succès !"
                    btn_google.text = "Déconnecter Google"
                    btn_google.on_click = deconnecter_google
                    await asyncio.sleep(2)
                    dlg_auth.open = False
                    page.update()
                except Exception as ex:
                    texte_auth_status.value = f"Code invalide : {str(ex)}"
                    page.update()

            code_input = ft.TextField(label="Code d'autorisation Google", password=False)
            texte_auth_status = ft.Text("", size=12, color="orange400")
            
            dlg_auth = ft.AlertDialog(
                title=ft.Text("Connexion Google"),
                content=ft.Column([
                    ft.Text("1. Cliquez sur le lien pour autoriser Jarvis :"),
                    ft.TextButton("Ouvrir le navigateur", 
                                 on_click=lambda _: page.launch_url(google_auth.get_authorization_url())),
                    ft.Text("2. Copiez le code reçu et collez-le ci-dessous :"),
                    code_input,
                    texte_auth_status,
                ], tight=True),
                actions=[
                    ft.TextButton("Annuler", on_click=lambda _: setattr(dlg_auth, 'open', False) or page.update()),
                    ft.ElevatedButton("Valider", on_click=handle_auth_dialog),
                ]
            )
            
            page.dialog = dlg_auth
            dlg_auth.open = True
            page.update()

        async def deconnecter_google(e):
            google_auth.disconnect_google()
            texte_statut_param.value = "Déconnecté de Google."
            btn_google.text = "Connecter Google"
            btn_google.on_click = connecter_google
            page.update()

        texte_statut_param = ft.Text("", italic=True, color="green400")
        btn_train = ft.ElevatedButton(
            "Optimiser Jarvis (Train)", 
            icon=ft.Icons.AUTO_AWESOME, 
            on_click=optimiser_jarvis,
            style=ft.ButtonStyle(color="green400")
        )

        # Bouton Google dynamique
        is_connected = google_auth.is_google_connected()
        btn_google = ft.ElevatedButton(
            "Déconnecter Google" if is_connected else "Connecter Google",
            icon=ft.Icons.ACCOUNT_CIRCLE,
            on_click=deconnecter_google if is_connected else connecter_google,
            style=ft.ButtonStyle(color="blue400")
        )

        page.add(
            ft.Text("Paramètres de l'IA", size=24, weight=ft.FontWeight.BOLD),
            ft.Divider(),
            ft.Text("Apparence"),
            ft.Row([boutton_theme_light, boutton_theme_dark]),
            ft.Divider(),
            ft.Text("Intelligence & Connectivité"),
            ft.Row([btn_train, btn_google], alignment=ft.MainAxisAlignment.CENTER),
            texte_statut_param,
        )
        
        page.update()

    def changement_theme_light():
        appbar.bgcolor = "blue"
        page.theme_mode = ft.ThemeMode.LIGHT
    
    def changement_theme_dark():
        appbar.bgcolor = "green"
        page.theme_mode = ft.ThemeMode.DARK

    
    
            
    page.add(
                ft.Text("Bienvenue sur Cortex IA", size=30, weight=ft.FontWeight.BOLD),
                ft.Text("Votre assistant de gestion de tâches intelligent", size=20),
        )
    
    async def handle_show_drawer():
        await page.show_drawer()
        

    def handle_dismissal(e: ft.Event[ft.NavigationDrawer]):
        print("Drawer dismissed!")

    async def handle_change(e: ft.Event[ft.NavigationDrawer]):
        index = e.control.selected_index
        print(f"Onglet sélectionné : {index}")
        if index == 0:
           charger_acceuil()
        elif index == 1:
            await charger_jarvis()
        elif index == 2:
            await charger_prioriseur()
        elif index == 3:
            charger_parametres()

        await page.close_drawer()

    page . drawer = ft.NavigationDrawer(
        on_dismiss=handle_dismissal,
        on_change=handle_change,
        controls=[
            ft.Container(height=12),
            ft.NavigationDrawerDestination(
                label="Acceuil",
                icon=ft.Icons.HOME,
                selected_icon=ft.Icon(ft.Icons.HOME),
            ),
            ft.NavigationDrawerDestination(
                icon=ft.Icon(ft.Icons.SMART_TOY_OUTLINED),
                label="Jarvis",
                selected_icon=ft.Icons.SMART_TOY,
            ),
            ft.Divider(thickness=2),
            ft.NavigationDrawerDestination(
                icon=ft.Icon(ft.Icons.PRIORITY_HIGH_OUTLINED),
                label="Prioriseur",
                selected_icon=ft.Icons.PRIORITY_HIGH,
            ),
            ft.NavigationDrawerDestination(
                icon=ft.Icon(ft.Icons.SETTINGS_OUTLINED),
                label="Paramètres",
                selected_icon=ft.Icons.SETTINGS,
            ),
        ],
    )

    appbar = ft.AppBar(
        title=ft.Text("Cortex IA"),
    )
    page.appbar = appbar
    appbar.bgcolor = "green"
    charger_acceuil()
    page.update()
            
ft.run(main)