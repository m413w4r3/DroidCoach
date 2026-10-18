# PLAN_EXECUTION — ATLAS VM-0 / VM-1

Source des lots : `docs/09-plan-lots-et-recette-vm.md` (prévaut). Orchestrateur : session Nimbalyst principale.

## Inventaire d'environnement (relevé 2026-10-18)

| Élément | Valeur |
|---|---|
| Arch | x86_64, Linux 6.8 |
| JDK | OpenJDK 21.0.12 |
| Gradle système | 4.4.1 (inutilisable → wrapper obligatoire, distributions en cache : 8.9-bin, 8.10.2-bin/all) |
| SDK | `/home/nill/Android/Sdk` (platforms 23…35, 37.0, 37.1 ; build-tools 34.0.0, 35.0.0…) |
| adb | 34.0.4 ; emulator présent ; `avdmanager`/`sdkmanager` hors PATH (cmdline-tools dans le SDK) |
| AVD | `Pixel_9_Pro_XL`, `Pixel_9_Pro_XL_API_30_2` ; **emulator-5554 actif : Android 11 / API 30 / x86_64** |
| KVM | `/dev/kvm` root:kvm 660 ; utilisateur hors groupe kvm (émulateur déjà en marche, non bloquant) |
| Git | dépôt **non initialisé** → pas de worktrees : écritures sérialisées / dossiers disjoints |
| Réseau | accessible (services.gradle.org répond) |
| Pixel réel | non supposé |

## Décisions verrouillées (voir aussi `docs/DECISIONS_AUTOMATIQUES.md`)

- Package / namespace : `dev.atlas.coach` ; applicationId `dev.atlas.coach` (debug suffix interdit pour simplifier adb).
- Un seul module Gradle `:app`. Kotlin + Compose, minSdk 26, compileSdk/targetSdk 35 (à ajuster si SDK/AGP l'impose, tracer).
- Cible de test émulateur : `emulator-5554` (API 30). Ne jamais effacer les AVD.
- Source de vérité des interfaces : `docs/09` § « Interfaces Kotlin minimales » ; S0 crée les fichiers de types/interfaces partagés dans `dev.atlas.coach.core` ? **Non** : chaque domaine possède ses types (focus/, habits/, coach/, rules/) ; S3 attache.

## Lots, ordre et propriétaires de fichiers

| Lot | Dépend | Propriétaire exclusif |
|---|---|---|
| S0 Fondation + VM-0 | — | `android/` Gradle/wrapper/manifest/`app/app/`, `scripts/`, `docs/VALIDATION_VM.md`, `docs/captures/` |
| S1 focus+rules | S0 | `…/focus/**`, `…/rules/**` + tests |
| S2 habits+coach+content | S0 | `…/habits/**`, `…/coach/**`, `…/content/**` + tests |
| S3 intégration UI/VM-1 | S1+S2 | tout fichier global, `data/`, `settings/`, navigation |
| S4 backend | VM-1 | `backend/**` |
| S5 revue | S3(+S4) | lecture seule, corrections via propriétaire |

Concurrence max 3 ; S1 ‖ S2 uniquement après S0 validé.

## Journal d'orchestration

(voir `docs/VALIDATION_VM.md` pour les preuves ; sessions Nimbalyst listées ci-dessous au fil de l'eau)
