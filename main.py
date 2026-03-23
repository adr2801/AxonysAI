import time
import flet as ft
import numpy as np
import IA_base


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

    def charger_prioriseur():
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
            tableau
        )
        page.update()

    def charger_parametres():
        page.clean()
        boutton_theme_light = ft.Button("Thème clair", on_click=lambda e: setattr(page, 'theme_mode', ft.ThemeMode.LIGHT))
        boutton_theme_dark = ft.Button("Thème sombre", on_click=lambda e: setattr(page, 'theme_mode', ft.ThemeMode.DARK))
        page.add(
            ft.Text("Paramètres de l'IA", size=24, weight=ft.FontWeight.BOLD),
            boutton_theme_light,
            boutton_theme_dark,
        )
        
        
        page.update()

    def delete_row(e):
        # 'e.control.data' contient la ligne (DataRow) associée au bouton
        row_to_delete = e.control.data
        tableau.rows.remove(row_to_delete)
        tableau.update()

    tableau = ft.DataTable(
        columns=[
            ft.DataColumn(ft.Text("Fait")),
            ft.DataColumn(ft.Text("Tâche")),
            ft.DataColumn(ft.Text("Score (%)"),numeric=True),
        ],
        rows=[]
    )

    def calculer_priorite(e):
        chargement.visible = True
        page.update()
        time.sleep(1)
        chekcbox = ft.Checkbox()
        if slider_dur.value == 0  or slider_ene.value == 0 or slider_env.value == 0 or slider_imp.value == 0 or slider_urg.value == 0:
            score = 0
        else :
            input_data = np.array([[slider_urg.value/10, slider_imp.value/10, slider_dur.value/10, slider_env.value/10, slider_ene.value/10]])
            score = ia.forward(input_data)[0][0]
        tableau.rows.append(ft.DataRow(cells=[ft.DataCell(chekcbox),ft.DataCell(ft.Text(nom_tache.value)), ft.DataCell(ft.Text(f"{score*100:.1f}"))]))
        tableau.rows.sort(key=lambda row: float(row.cells[2].content.value.replace('%', '')), reverse=True)

        chargement.visible = False
        texte_statut.value = "Priorité calculée"

        nom_tache.value = ""
            
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
            charger_prioriseur()
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

    charger_acceuil()
    page.update()
            
ft.run(main)