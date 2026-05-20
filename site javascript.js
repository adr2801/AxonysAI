document.addEventListener("DOMContentLoaded", () => {
    // Gestion de la navigation par onglets
    const tabs = document.querySelectorAll(".tab-btn");
    const contents = document.querySelectorAll(".tab-content");

    tabs.forEach(btn => {
        btn.addEventListener("click", () => {
            const target = btn.getAttribute("data-target");

            // Nettoyage des états actifs
            tabs.forEach(t => t.classList.remove("active"));
            contents.forEach(c => c.classList.remove("active"));

            // Activation du nouvel onglet
            btn.classList.add("active");
            document.getElementById(target).classList.add("active");
        });
    });
});

// Validation e-mail
function changerAdresse() {
    const input = document.getElementById("adresse");
    const feedback = document.getElementById("msg-retour");
    const email = input.value;

    if (email.match(/^[^\s@]+@[^\s@]+\.[^\s@]+$/)) {
        feedback.textContent = "Accès autorisé : " + email;
        feedback.style.color = "#4ade80";
        input.style.borderColor = "#4ade80";
    } else {
        feedback.textContent = "Format e-mail invalide.";
        feedback.style.color = "#f87171";
    }
}

document.addEventListener("DOMContentLoaded", function () {
    var modal = document.getElementById("privacyModal");
    var btn = document.getElementById("privacyBtn");
    var span = document.getElementsByClassName("close")[0];

    if (btn && modal && span) {
        btn.onclick = function () {
            modal.style.display = "block";
            document.body.style.overflow = "hidden";
        }
        span.onclick = function () {
            modal.style.display = "none";
            document.body.style.overflow = "auto";
        }
        window.onclick = function (event) {
            if (event.target == modal) {
                modal.style.display = "none";
                document.body.style.overflow = "auto";
            }
        }
    }
});