import time
import flet as ft
import numpy as np
import IA_base
import threading
import json


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

    async def calculer_priorite(e):
        global nouvelle_ligne
        global chekcbox
        chargement.visible = True
        chekcbox = ft.Checkbox()
        page.update()
        time.sleep(1)
        if slider_dur.value == 0  or slider_ene.value == 0 or slider_env.value == 0 or slider_imp.value == 0 or slider_urg.value == 0:
            score = 0
        else :
            input_data = np.array([[slider_urg.value/10, slider_imp.value/10, slider_dur.value/10, slider_env.value/10, slider_ene.value/10]])
            score = ia.forward(input_data)[0][0]
        nouvelle_ligne = ft.DataRow(
            cells=[
                ft.DataCell(chekcbox), # Colonne 1 : La Checkbox
                ft.DataCell(ft.Text(nom_tache.value)), # Colonne 2 : Nom
                ft.DataCell(ft.Text(f"{score*100:.1f}%")), # Colonne 3 : Score
            ]
        )
        tableau.rows.append(nouvelle_ligne)
        tableau.rows.sort(key=lambda row: float(row.cells[2].content.value.replace('%', '')), reverse=True)
        chekcbox.on_change = au_changement
        await sauvegarder_automatique()
        chargement.visible = False
        texte_statut.value = "Priorité calculée"

        nom_tache.value = ""

    async def charger_donnees_sauvegardees():
        if await page.shared_preferences.contains_key("mes_taches"):
            txt_sauvegarde = await page.shared_preferences.get("mes_taches")
            sauvegarde = json.loads(txt_sauvegarde)
            for item in sauvegarde:           
                chekcbox = ft.Checkbox(value=item["termine"])

                nouvelle_ligne = ft.DataRow(cells=[
                    ft.DataCell(chekcbox),
                    ft.DataCell(ft.Text(item["nom"])),
                    ft.DataCell(ft.Text(item["score"]))
                ])

                chekcbox.on_change = au_changement
                if chekcbox.value == True:
                    t = threading.Thread(target=verifier_et_supprimer, args=(nouvelle_ligne, chekcbox))
                    t.start()

                tableau.rows.append(nouvelle_ligne)

    async def sauvegarder_automatique():
        donnees = []
        for row in tableau.rows:
            if row == verifier_et_supprimer(row , chekcbox) :
                pass
            else :
                donnees.append({
                    "termine": row.cells[0].content.value,
                    "nom": row.cells[1].content.value,
                    "score": row.cells[2].content.value
                })
        liste_en_texte = json.dumps(donnees)
        await page.shared_preferences.set("mes_taches", liste_en_texte)

    async def au_changement(e):
        await sauvegarder_automatique()
        if chekcbox.value == True:
            # On lance le thread de 10 secondes
            t = threading.Thread(target=verifier_et_supprimer, args=(nouvelle_ligne, chekcbox))
            t.start()

    def verifier_et_supprimer(ligne, checkbox):
        # On attend 10 secondes
        time.sleep(10)
        
        # On vérifie si elle est toujours cochée après le délai
        if checkbox.value == True:
            if ligne in tableau.rows:
                tableau.rows.remove(ligne) 
                page.update()
                print("Tâche terminée et supprimée !")
        return ligne

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