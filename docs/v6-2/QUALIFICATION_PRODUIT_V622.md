# ATLAS V6.2.2 — qualification produit après S6

**Verdict : qualification partielle; les résultats ACK ciblés varient selon la cible.** Sur la paire finale app `ce10c968…` / AndroidTest `893da1cf…`, un rejeu supplémentaire le 21 octobre (11:37–11:42 UTC) a reconfirmé le test corrigé 1/1, la suite principale 75/75 à 360 dp et les deux passages Gateway puis hors ligne 1/1. Les sorties runner de ce rejeu ont des empreintes distinctes dans la [preuve de restauration exacte](preuves-v622/s6-revocation-fixture-exact-restore-20261021.txt). La fixture restaure maintenant tout le fichier de préférences de révocation à son instantané exact; si l'instantané omet des clés, `@After` rétablit leur absence et l'égalité est vérifiée. Les huit compléments sûrs et la recette localisation restent des résultats historiques sur `0b5c…`; ils ne sont pas transférés à `893da1…`. Le premier run principal à la géométrie par défaut 411 dp avait 2 échecs de largeur; après configuration à 360 dp, les deux captures et le run principal ont passé. Aucun défaut de production n'a été établi ni corrigé. Sur la paire ACK S5 `7c6c/542280`, l'AVD API34 passe les deux méthodes; le Pixel 9 Pro XL API37 passe la course de révocation mais échoue de façon reproduite sur le chemin positif. Le harnais complet et les scénarios UsageStats restent `NOT_RUN`; D3, recette physique complète et TalkBack réel restent `NOT_RUN`; l'environnement AVD Android 17 S4 reste `BLOCKED`.

## Résultats

Le handoff S5 final pour les deux méthodes ACK est séparé du résultat accepté
`ce10/893da1` : hashes locaux app `7c6c41a4…` / AndroidTest `542280c3…` et
manifeste 234 entrées `PASS`. Sur `emulator-5580` / API34, les deux méthodes
passent `OK (1 test)` et les APK installées ont les hashes attendus. Après arrêt
de l'AVD, le contrôle physique autorisé sur Pixel 9 Pro XL / API37 passe la
course ACK mais échoue de façon reproduite sur le chemin positif. Les packages
étaient absents avant le contrôle, les fixtures préfixées ont été nettoyées,
les notifications actives restent à zéro et l'absence a été vérifiée après le
premier run. Une reprise ultérieure a été interrompue avant instrumentation
après un script contenant `install -r`; son état package final est
`NOT_RECHECKED`.
Voir [le rapport runtime API34](preuves-v622/s6-ack-api34-runtime-20261021.txt)
et [la preuve physique](preuves-v622/s6-ack-physical-runtime-20261021.txt).

| Domaine | Résultat S6 | Limite de qualification |
| --- | --- | --- |
| Correction AndroidTest de quarantaine et fixture | `PASS 1/1` sur AndroidTest `893da1…` | Vérifie schéma, catégories, politique invalide et absence des deux clés privées; restaure et compare exactement le fichier SharedPreferences dans `@After` |
| Régressions AndroidTest ciblées précédentes | `20/20 PASS` sur AndroidTest `8edb…` | Résumés runner conservés; non rejouées sur la paire `893da1…` |
| Suite principale AndroidTest actuelle | `PASS 75/75` sur AndroidTest `893da1…`; AndroidJUnitRunner `OK (75 tests)` | Compte fixé à 75 avant le run; viewport vérifié 360 dp; harnais complet non lancé |
| Compléments non destructifs | `PASS 8/8` historique sur AndroidTest `0b5c…`; `NOT_RUN` sur `893da1…`; 2 autres `NOT_RUN` | Les deux cas UsageStats effacent l'état de consentement/historique; le script complet appelle en plus deux `pm clear` |
| Compose / UI D1 | `PASS` sur la suite principale et les deux captures HOME/Coach ciblées à 360 dp, paire `893da1…` | Les compléments 320 dp/paysage plus larges restent historiques `0b5c…`; captures privées AVD uniquement |
| Localisation visible | Résultats antérieurs `0b5c…` : logique, activité principale et préparation mobilité `PASS`; acquisition `TEST_SYSTEM_BLOCKED` | Non rejouée sur `893da1…`; Android n'avait pas retourné de fix coarse frais après injection synchronisée |
| E2E OpenClaw réel | `PASS` 2/2 passages Gateway puis hors ligne 1/1 sur app `ce10…` / AndroidTest `893da1…` | Gateway réel et adaptateur `openclaw`; deux ACK exécutés avec effet. `V6RealAgentE2e` est `NOT_RUN` sur `893da1…`; son `FAIL` antérieur sur `8edb…` reste non classé |
| Course ACK et chemin positif ACK — paire S5 `7c6c/542280` | API34 AVD : `PASS 1/1` chaque méthode; Pixel 9 Pro XL API37 : course `PASS`, chemin positif `FAIL` reproduit | Hashes installés conformes sur les deux cibles; scope limité aux deux méthodes, aucun autre scénario n'a été transféré. Packages physiques absents restaurés après retrait ciblé. Voir [preuve API34](preuves-v622/s6-ack-api34-runtime-20261021.txt) et [preuve physique](preuves-v622/s6-ack-physical-runtime-20261021.txt) |
| Persistance hors ligne et processus OS | `PASS` sur `893da1…` : backend arrêté, force-stop vérifié puis relance par Activity Manager | Aucun essai physique de persistance ou hors ligne dans le contrôle ACK; aucun `pm clear` ou wipe |
| TalkBack | `PARTIAL` historique sur AVD : service lié et sémantique UI inspectée | Parole, gestes et ordre d'écoute non vérifiés; `TALKBACK_REAL_PASS` absent |
| CPU / réveils / WorkManager / réseau | D3 `NOT_RUN` sur `893da1…`; mesure coaching non tentée | Aucun test UsageStats ni consentement coaching exécuté dans cette reprise. La fenêtre locale 60 s et le blocage coaching antérieurs restent historiques |
| AVD Android 17 / API 37 S4 | `BLOCKED` avant installation; 17 scénarios A2 et 11 nouveaux AndroidTests du candidat final `NOT_RUN` | Services framework instables, `system_server` mort et assertions natives UWB/renderer. Issue 1 reste ouverte; aucun nouvel essai de cet environnement AVD n'a été lancé. Le contrôle physique API37 est distinct. |
| Téléphone physique | `PARTIAL` — deux méthodes ACK seulement sur Pixel 9 Pro XL / API37 | Course ACK `PASS`; chemin positif `FAIL` reproduit. Nettoyage du premier run vérifié; une reprise a été interrompue avant instrumentation après une commande contenant `install -r`, donc l'état package post-interruption est `NOT_RECHECKED`. Aucun parcours physique complet, test d'accessibilité ou mesure batterie |
| Baseline JVM, lint, APK et backend | `PASS` historique : 315 cas JVM uniques, lint, assemblages app/test; pytest 66 passed, 1 skipped, 0 failed | Baseline antérieure à la correction AndroidTest-only; la reconstruction de cette fixture a exécuté `:app:assembleDebugAndroidTest` et les tests unitaires localisation ciblés. Le rejeu supplémentaire décrit ci-dessus n'a lancé aucun Gradle |

Les détails opératoires et sorties se trouvent dans [RECETTE_V622.md](RECETTE_V622.md), l'[index des preuves](INDEX_PREUVES_V622.md) et [la dernière preuve de restauration exacte et des rejeux `893da1`](preuves-v622/s6-revocation-fixture-exact-restore-20261021.txt). Les preuves `0b5c` et `8edb` restent historiques et distinctes. Les résultats `ea53…` antérieurs sont conservés dans [la preuve candidate reçue](preuves-v622/s6-candidat-s5-s7-p1-01-final.txt). Le résultat Android 17 est documenté dans [s6-android17.txt](preuves-v622/s6-android17.txt), le [rapport S4](ANDROID17_V622.md) et [la preuve du candidat final](preuves-v622/android17-v622-final-candidate.txt). Les sorties complètes des scénarios sensibles restent sous `/tmp/atlas-v622-s6/`; seules des synthèses expurgées sont versionnées.

## Verdict d'usage

Le défaut rouge initial de migration est attribué au fixture qui laissait une barrière de révocation persistante active; la correction test-only et la restauration exacte passent sur AndroidTest `893da1…`. Les 75 cas principaux et les E2E dédiés passent sur cette paire. Les résultats plus larges D1 et localisation de `0b5c…` restent séparés. Le contrôle ACK sur un Pixel 9 Pro XL est limité à deux méthodes et comprend un échec reproduit du chemin positif; il ne qualifie pas le produit sur téléphone. Le harnais complet, les deux compléments UsageStats et D3 restent non exécutés. Ne pas communiquer de qualification générale téléphone, « GPS validé », « TalkBack validé » ou « batterie qualifiée ».

La branche demeure `feat/atlas-v6-2-2`, HEAD `bf647cc18a142dd0f5cd5e68b21c36d511014388`, avec modifications non commitées héritées de S5 et les ajouts S6 de tests/scripts/documents. Aucun commit, push, merge ou tag n'a été créé.
