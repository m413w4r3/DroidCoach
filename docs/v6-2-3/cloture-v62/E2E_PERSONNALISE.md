# E2E personnalisé — parcours local et OpenClaw

## Parcours local sans serveur — PASS

Source de la campagne Pixel : `8890046012965f3a3112ff0c419014c74e189d11`; APK `48c7766e…`. Source finale du code : `1a6f761`; APK final AVD `dc1c3e00…`.

Parcours réellement exécuté sur le Pixel, avec des données synthétiques :

1. Objectif « QA V6.2 objectif 120 min » créé, métrique `focus_minutes`, cible 120.
2. Focus de 25 min explicitement lié démarré à `2026-10-25T22:47:09Z` (heure locale du téléphone); avant la fin, progression **0 / 120 min**.
3. Session terminée : état `FINISHED`, 1500 s de travail, session liée à l'objectif.
4. Arrêt de processus puis relance à froid : `Status: ok`, état `COLD`, 583 ms; l'interface Coach affiche **25 / 120 min (20 %)**.
5. Nouvelle session Focus liée de 25 min démarrée puis annulée après ~2 s : l'interface affiche toujours **25 / 120 min**; aucune minute créditée.
6. Pause d'urgence : activation et fin vérifiées séparément (matrice Pixel).

**Verdict Pixel : PASS** pour la progression 0 → 25 → 25 après redémarrage et l'annulation sans crédit. Limite : ce parcours a été exécuté sur l'APK `8890046…`, avant le correctif de mandat `1251009`. Ce correctif ne modifie ni `FocusEngine`, ni le calcul de progression, ni le lien objectif/Focus; il ne touche que les propositions d'agent. Le propriétaire a demandé l'arrêt des tests physiques après ce parcours.

La suite JVM finale sur `1a6f761` passe **339 tests, 0 échec**. Elle comprend notamment :

- `FocusEngineTest#pauseResumeFinishAndCancelAreIdempotent` et `#snapshotRestoresRunningTimerUsingBoundedWallClockElapsedTime`.
- `GoalProgressCalculatorTest#elapsedPomodoroWorkExcludesBreakAndCancelledWorkWithAControlledClock`.
- `GoalPanelCoordinatorFocusLinkTest#pausedPomodoroGetsNoCreditThenFinishedWorkSurvivesRecreationAndRepeatedRecalculation`.
- `GoalPersistenceTransactionTest#failedCreateUpdateStatusConfirmationAndLinkWritesKeepCacheEqualToDiskAfterRecreation`.
- Les nouveaux tests `MandateVerifier` de durée totale et de fin de fenêtre.

Ces tests JVM/AVD ne remplacent pas le parcours Pixel; ils complètent la couverture du SHA final.

## Backend réel et adaptateur OpenClaw

### Run host-side réel — 2026-10-24T22:40:46Z

Le gateway local a répondu HTTP 200. Le backend ATLAS éphémère, lié à `127.0.0.1`, a accepté le Bearer généré (200) et refusé un Bearer invalide (401). Le payload réel est résumé dans [preuves-openclaw/host-run.txt](preuves-openclaw/host-run.txt) :

- le gateway a reçu `/v1/chat/completions`, modèle `openclaw/atlas`, avec le Focus synthétique `completed | 25 min | 1 session` et l'objectif synthétique 120 min;
- la réponse réelle reprend « 25 min validées sur 120 min » et propose une prochaine action;
- les propositions `home.set_directive` et `focus.propose` respectent les actions autorisées du mandat;
- le rejeu du même ACK est stable et laisse une seule ligne de journal;
- après retrait de `goals`, la requête suivante ne transmet que `focus`;
- gateway indisponible, le backend renvoie le fallback sans proposition persistée.

**Verdict host-side : PASS.** Ce run ne vérifie pas `MandateVerifier` dans l'application Android, l'effet local Android ni le refus d'action pendant une révocation en vol.

### Test Android OpenClaw sur AVD API 37 — 2026-10-24T22:56Z

Le parent a exécuté sur `emulator-5554` (Android 17/API 37) :

`V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal`

avec un backend ATLAS éphémère lié à `127.0.0.1`, atteint par l'émulateur via `10.0.2.2`, et le gateway OpenClaw réel. Résultat : **OK (1 test), 11.143 s**. Le test a vérifié `agent_status=CONNECTED`, le contexte Focus/objectif, la proposition acceptée et `EXECUTED`, la directive HOME, l'ACK backend et l'effet dans le journal, puis le retrait de `goals` dans la requête suivante. Preuve brute sans jeton : [preuves-openclaw/android-avd-run.txt](preuves-openclaw/android-avd-run.txt).

### Réexécution sur le SHA final `1a6f761` — PASS

Le 2026-10-25, après réinitialisation du quota Codex et installation de l'APK final `dc1c3e00…` sur `emulator-5554`, le test a été relancé avec un backend ATLAS éphémère atteint via `10.0.2.2` et le gateway OpenClaw réel. Résultat : **OK (1 test), 13,817 s**. La sortie brute sans jeton est dans [preuves-finales-1a6f761/openclaw-android.txt](preuves-finales-1a6f761/openclaw-android.txt).

Un premier essai sur `1251009` avait été bloqué par `HTTP 429` (`Codex error: The usage limit has been reached`), avec le réseau émulateur et le backend sains. Après réinitialisation du quota, le test final a réussi; le run bloqué reste un fait historique, pas un PASS.

La révocation réellement *pendant* une requête en vol et le fallback Android restent des assertions distinctes non couvertes par ce seul test; les tests locaux de génération, de barrière de révocation et de réponse tardive sont PASS.
