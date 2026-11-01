# ATLAS V7-06C — Coach Android puis transport public : rapport final

**Statut du document : EN COURS — aucun verdict final n’est prononcé.**
Les sections sont mises à jour au fur et à mesure des exécutions réelles sur le SHA
intégré. Une ligne `NOT_RUN` ou `BLOCKED` ne doit pas être lue comme un succès.

**Campagne :** V7-06C « Coach Android puis transport public »
**BASE_SHA :** `3e0d1f4c9ca591f903a894be87183395d30fee0b`
**Branche :** `feat/atlas-v7-06c-android-coach-loop`
**Registre :** `docs/operations/REGISTRY_V7_06C.md`
**Date de lancement :** 2026-11-01

## 1. Résultat exécutif

À ce stade, le pré-vol est `ACCEPTED (lecture seule)` et le harnais reproductible est en
cours de construction. Aucun test instrumenté Android n’a encore été exécuté pour
V7-06C ; aucun ACK Android, aucune application v2→v3 et aucun rejet Android ne sont
revendiqués. Le transport public est `BLOCKED_NETWORK` : le Funnel pointe vers
`127.0.0.1:4400`, sans listener, et la sonde TLS échoue.

## 2. Périmètre et provenance

- Le code et les preuves V7-06A/V7-06B déjà acceptés ne sont ni rejoués ni réécrits sans
  raison technique.
- Le test Android réutilisé est
  `V7_06B_CoachLoopInstrumentedTest` avec son runner
  `scripts/v7-06b/android/run_qa.py`, sous un nouveau package QA
  `dev.atlas.coach.qa.v706b.v706c…`.
- Le paquet produit `dev.atlas.coach`, HOME, Keystore et données personnelles ne sont
  pas touchés.
- Le modèle réel de campagne reste `deepseek/deepseek-chat` via un Gateway OpenClaw
  isolé ; aucun profil OAuth personnel n’est copié.
- La stack isole Core, SQLite, Relay épinglé, connecteur, Gateway, proxy et identités
  synthétiques ; aucun service live n’est modifié.

## 3. Harnais reproductible (V7-06C-01)

Statut : **EN COURS**. Le harnais V7-06B non commité sous
`/tmp/atlas-v7-06b-backend` a été audité ; ses éléments utiles sont portés sous
`scripts/v7-06c/` avec les correctifs déjà connus (`expires_at` 30 s, arrêt Gateway,
stderr en fichiers privés 0600, readiness réelle, oracles indépendants).

À compléter : chemins exacts, commits, commandes de reproduction, tests du harnais,
résultats backend réels.

## 4. Android APPLY

Statut : **NOT_RUN**.

À compléter uniquement après exécution réelle : appareil et API, SHA source,
applicationId, hashes APK, signature, résultat du runner, trace expurgée, oracle Core
indépendant, plan final, proposition, ACK, nombre d’événements, révocation, limites.

## 5. Android REJECT

Statut : **NOT_RUN**.

À compléter uniquement après exécution réelle : plan resté v2, aucun v3, ACK REJECTED
durable, idempotence, révocation, oracle Core.

## 6. Transport public WSS

Statut : **BLOCKED_NETWORK — NOT_RUN**.

Constat de pré-vol (lecture seule) :

- DNS public `all.taildc7886.ts.net` résolu.
- Funnel actif `tcp://all.taildc7886.ts.net:8443` → `127.0.0.1:4400`.
- Aucun listener sur `127.0.0.1:4400`.
- Le relais live publie `4000/tcp -> 127.0.0.1:4402`.
- Sonde TLS `https://all.taildc7886.ts.net:8443/health` : erreur `SSL_ERROR_SYSCALL`.

Aucun changement Funnel/Tailscale, service live ou secret n’a été effectué. Une
autorisation ciblée et un endpoint vivant sont requis avant toute recette publique.

## 7. Sécurité et identités résiduelles

Le pré-vol V7-06C a relu l’audit V7-06A. Les consentements live `DAY_PLAN` et
`ACTIVITY_STATUS` `GRANTED v2` et les appareils `pixel-v7`/`r3c-pixel-final` restent
hors périmètre de cette campagne tant qu’aucune autorisation ciblée de révocation
n’est donnée. Aucune identité personnelle n’est utilisée par la stack V7-06C.

## 8. Suites et preuves

À exécuter sur le SHA intégré : backend pytest complet, connecteur Node, plugin
OpenClaw, Android JVM, lint, builds debug/QA/AndroidTest, tests du harnais,
`git diff --check`, revue indépendante.

## 9. Verdicts

| Verdict | Décision | Portée |
|---|---|---|
| `GO_E2E_HARNESS_REPRODUCIBLE` | **NOT_RUN** | harnais en construction |
| `GO_ANDROID_QA_CHAT` | **NOT_RUN** | aucun chat Android exécuté |
| `GO_ANDROID_QA_PLAN_APPLY` | **NOT_RUN** | aucune application Android exécutée |
| `GO_ANDROID_QA_REJECT` | **NOT_RUN** | aucun rejet Android exécuté |
| `GO_ANDROID_QA_ACK` | **NOT_RUN** | aucun ACK Android confirmé |
| `GO_QA_COACH_LOOP` | **NOT_RUN** | APPLY et REJECT non exécutés |
| `GO_REMOTE_V4_PUBLIC` | **NO GO — BLOCKED_NETWORK** | Funnel cible morte, aucune autorisation de changement |
| `GO_PIXEL_PRODUCT` | **NO GO** | paquet produit non testé |
| `GO_PRODUCT_REMOTE` | **NO GO** | produit et transport public non testés |

## 10. Revue indépendante

Statut : **PENDING**. Aucune revue ne peut accepter un runtime non exécuté.

## 11. Limites honnêtes

- Un test compilé n’est pas un test exécuté.
- Une proposition Core n’est pas une modification Android appliquée.
- Un ACK construit par le harnais backend n’est pas un ACK émis par Android.
- Un succès local ne vaut pas `GO_REMOTE_V4_PUBLIC`.
- Un succès sur paquet QA ne vaut pas `GO_PIXEL_PRODUCT`.
