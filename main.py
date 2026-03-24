import time
import flet as ft
import numpy as np
import IA_base
import threading
import json
import asyncio

def main(page: ft.Page):
    page.title = "Cortex IA"
    page.assets_dir = "icon.png"
    page.theme_mode = ft.ThemeMode.DARK
    page.scroll = ft.ScrollMode.ADAPTIVE
    
    
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

        # Calcul du score
        if any(v == 0 for v in [slider_dur.value, slider_ene.value, slider_env.value, slider_imp.value, slider_urg.value]):
            score = 0
        else:
            input_data = np.array([[slider_urg.value/10, slider_imp.value/10, slider_dur.value/10, slider_env.value/10, slider_ene.value/10]])
            score = ia.forward(input_data)[0][0]

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

    

    
        
    def charger_parametres():
        page.clean()
        global boutton_theme_dark
        global boutton_theme_light
        boutton_theme_light = ft.Button("Thème clair", on_click=changement_theme_light)
        boutton_theme_dark = ft.Button("Thème sombre", on_click=changement_theme_dark)
        page.add(
            ft.Text("Paramètres de l'IA", size=24, weight=ft.FontWeight.BOLD),
            boutton_theme_light,
            boutton_theme_dark,
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
            await charger_prioriseur()
        elif index == 2:
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