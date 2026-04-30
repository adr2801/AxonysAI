import time
import flet as ft
import numpy as np
import IA_base

def main(page: ft.Page):
    page.title = "Cortex IA"
    page.theme_mode = ft.ThemeMode.LIGHT
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

    


    tableau = ft.DataTable(
        columns=[
            ft.DataColumn(ft.Text("Tâche")),
            ft.DataColumn(ft.Text("Score (%)"),numeric=True),
        ],
        rows=[] 
    )
    def calculer_priorite(e):
        chargement.visible = True
        page.update()
        time.sleep(0.5)
        input_data = np.array([[slider_urg.value/10, slider_imp.value/10, slider_dur.value/10, slider_env.value/10, slider_ene.value/10]])
        score = ia.forward(input_data)[0][0]
        tableau.rows.append(ft.DataRow(cells=[ft.DataCell(ft.Text(nom_tache.value)), ft.DataCell(ft.Text(f"{score*100:.1f}"))]))
        tableau.rows.sort(key=lambda row: float(row.cells[1].content.value.replace('%', '')), reverse=True)
        chargement.visible = False
        
        texte_statut.value = "Priorité calculée"
        
        nom_tache.value = ""
        page.update()
    page.add(
        chargement,
        texte_statut,
        ft.Text("Mon assistant Priorité", size=30, weight=ft.FontWeight.BOLD),
        nom_tache,
        ft.Text("Importance"),
        slider_imp,
        ft.Text("Urgence"),
        slider_urg,
        ft.Text("Durée(0 peu long 10 tres long)"),
        slider_dur,
        ft.Text("Envie de faire la tâche"),
        slider_env,
        ft.Text("Énergie nécessaire"),
        slider_ene,
        ft.Button("Ajouter et trier", on_click=calculer_priorite),
        ft.Divider(),
        ft.Text("Tableau des tâches prioritaires", size=20, weight=ft.FontWeight.BOLD),
        ft.Column([tableau], scroll=ft.ScrollMode.ADAPTIVE, height=400)
    )

ft.run(main)