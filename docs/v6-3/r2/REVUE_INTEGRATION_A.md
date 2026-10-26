# ATLAS V6.3 — R2 — Revue d'intégration de la vague A

Date : 2026-10-26. Relecture parent sur les diffs et oracles rejoués sur le SHA
intégré. Une notification de fin n'est pas une acceptation.

## 1. R2-CAL-01 — moteur d'écriture calendrier

| Élément | Valeur |
|---|---|
| Branche | `feat/r2-calendar-write` |
| Commit auteur | `595b224ff26ade5f95c11de0eb8061b83e489668` |
| Intégration | merge local sur la campagne |
| Scope | 6 fichiers neufs sous `calendar/write/**`, aucun fichier existant modifié |
| Oracle auteur | 18 tests ciblés PASS, suite JVM 496 PASS, `compileDebugKotlin` PASS |
| Oracle parent | à rejouer sur le SHA intégré |

Points forts :

- sélection explicite d'un calendrier `ACCOUNT_TYPE_LOCAL`, refus Google ;
- pas de second insert si l'URI existe déjà, refus si URI dupliquée ;
- journal `PREPARED` avant effet, `UNKNOWN` sans retry aveugle, réconciliation
  par URI ;
- refus des événements `FOREIGN`/`AMBIGUOUS` et des récurrences ;
- DST testé ;
- adaptateur Android compilé mais jamais invoqué en JVM.

Limites / P2 :

- **P2** — le rejeu idempotent d'une opération déjà `APPLIED` renvoie le lien
  courant sans relire le provider. Si l'événement a été supprimé ou modifié
  entre-temps, l'appelant doit explicitement appeler `reconcile(link)` pour
  obtenir `MISSING`/`DIVERGED`. Aucun doublon n'est créé, mais l'état affiché
  peut être optimiste. À couvrir avant activation réelle.
- L'implémentation de journal fichier atomique est un contrat documenté, pas
  encore fournie. Les tests utilisent le journal mémoire.
- Aucun calendrier local dans l'émulateur : la qualification CalendarContract
  réelle est `BLOCKED_ENVIRONMENT` ; aucun `PASS` réel n'est revendiqué.

Verdict : **ACCEPTED_WITH_P2** pour le code ; activation réelle non approuvée.

## 2. R2-UI-01 — palettes d'accent et sémantique

| Élément | Valeur |
|---|---|
| Branche | `feat/r2-ui-accent` |
| Commits auteur | `3b06030cd2cab549e3168233457b884e796d7985`, correctif `f0c136f7ed85acd03b7a1b0a075d3e967b930e60` |
| Intégration | merge local + correctifs parent `54b4183` |
| Scope | `app/ui/**` et tests UI uniquement |
| Oracle parent | tests UI ciblés PASS après correctifs |

Points forts :

- quatre accents sobres, défaut classique inchangé ;
- préférence persistante séparée du mode ;
- AMOLED conserve un fond noir même si la couleur dynamique est demandée ;
- rôles sémantiques et glyphes distincts ; glyphe exclu de TalkBack ;
- contrastes AA testés sur 3 bases × 4 accents ;
- appel `AtlasTheme(preference = ...)` inchangé.

Correctifs parent pendant l'intégration :

1. import manquant de la propriété de sémantique `selected` ;
2. variable locale `selected` masquant cette propriété ;
3. collision de signature JVM entre la propriété `dynamicColorEnabled` et la
   méthode `setDynamicColorEnabled` — méthode renommée `setDynamicColor`.

Limites :

- persistance réelle, rotation, police 2× et couleur dynamique Android restent
  à vérifier sur l'émulateur (vague D) ;
- aucun test instrumenté exécuté par l'auteur.

Verdict : **ACCEPTED** après correctifs parent ; recette visuelle à faire.

## 3. R2-SPORT-01 — projection sportive lecture seule

| Élément | Valeur |
|---|---|
| Branche | `feat/r2-sport-projection` |
| Commit auteur | `005f4a9bbd26cffb9612fddfeb646b62076a7bc8` |
| Intégration | merge local |
| Scope | 7 fichiers neufs, aucun fichier existant modifié |
| Oracle auteur | 8 tests backend ciblés PASS, 178 backend PASS / 1 skip, 7 JVM PASS |

Points forts :

- projection pure, sans I/O, sans écriture ;
- distinction stricte `planned` / `performed` ;
- IDs convertis en chaînes, bornes de taille, erreurs mappées sans jamais
  annoncer `FRESH` ;
- schéma `sport-plan-v1` fourni ;
- parseur Android strict, version inconnue refusée, fixture sans performance.

Limites :

- aucun appel wger réel dans ce ticket ; la lecture réelle reste qualifiée par
  R1 et ne doit pas être rejouée avec le refresh token ;
- le schéma est validé par un validateur local minimal, `jsonschema` n'étant
  pas installé dans le venv ;
- l'écran et le raccordement au plan sont faits en vague C.

Verdict : **ACCEPTED_WITH_P3** ; écran et intégration à faire.

## 4. État des gates

Voir `preuves/gates-wave-a.txt` pour les commandes et résultats exacts sur le
SHA intégré.
