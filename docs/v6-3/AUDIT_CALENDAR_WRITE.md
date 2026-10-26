# V63-L4 — Audit lecture seule du chemin d'écriture calendrier

| Élément | Valeur |
|---|---|
| TASK_ID | `V63-L4-SCOUT-01` |
| Session Paseo | `9b3b505a-9e11-4fe5-8d39-6cce70a91e8d` |
| Profil | `provider=codex`, `model=gpt-6-luna`, `effectiveThinkingOptionId=xhigh` |
| Base | `4dcb7cc66118bf74910584ce0da77682a51fd32a` |
| Worktree | `audit/v63-l4-calendar-write`, `chmod -R a-w`, HEAD et statut inchangés |
| Tests / ADB | **aucun** ; audit statique uniquement |

## 1. État réel de l'abstraction

L'abstraction `CalendarProvider` expose permission, liste de calendriers, lecture
d'événements et détection de package. **Aucun `insert`, `update`, `delete` ni
`applyBatch`** : le test vérifie explicitement cette absence
(`CalendarProvider.kt:4-16`, `AndroidCalendarProvider.kt:12-15`,
`CalendarReadServiceTest.kt:255-265`).

Le manifeste ne déclare ni `READ_CALENDAR` ni `WRITE_CALENDAR` ; l'adaptateur
vérifie seulement la déclaration et l'octroi de lecture
(`AndroidManifest.xml:1-10`, `CalendarReadService.kt:167-179`). `V63-L2-02`
reste `BLOCKED_APPROVAL` ; la séquence runtime Android 17/API 37 avec
`targetSdk 36` reste à qualifier.

## 2. Sélection d'un calendrier inscriptible

Le modèle lit `ACCOUNT_TYPE`, `CALENDAR_ACCESS_LEVEL`, `VISIBLE`,
`SYNC_EVENTS` et `IS_PRIMARY` (`CalendarModels.kt:13-25`). Le service filtre
les calendriers visibles avec accès ≥ 500, mais **ne sélectionne aucun
calendrier** et ne tient pas compte de `IS_PRIMARY`, `SYNC_EVENTS` ni du type
local.

Pour le premier lot L4 :
- choix **explicite** parmi les calendriers visibles et inscriptibles ;
- privilégier et qualifier `CalendarContract.ACCOUNT_TYPE_LOCAL` ;
- `IS_PRIMARY` = indice, `SYNC_EVENTS` = signal, jamais preuve de droit ;
- capacité réelle d'insertion non prouvée par le niveau d'accès seul ;
- si aucun calendrier local inscriptible : `BLOCKED_ENVIRONMENT`, sans création
  de calendrier, sans simulation de sync adapter, sans fallback Google.

## 3. Insertion d'un événement non récurrent

Colonnes à fournir : `CALENDAR_ID`, `DTSTART`, `DTEND`, `EVENT_TIMEZONE`,
`TITLE`, `ALL_DAY=0`, `CUSTOM_APP_PACKAGE=dev.atlas.coach`,
`CUSTOM_APP_URI=atlas://plan/{plan_id}/activity/{activity_id}`. Les heures sont
en millisecondes UTC, le fuseau un identifiant IANA. L'acceptation et la
conservation des marqueurs par le provider **ne sont pas qualifiées**
(`REFERENCES_INTEGRATION.md:51-64,87-95`).

## 4. Propriété et doublons

La lecture classe ATLAS seulement si les deux marqueurs sont cohérents ;
absence = `FOREIGN`, métadonnées partielles/invalides = `AMBIGUOUS`. Le code
n'a pas de recherche par `CUSTOM_APP_URI` ; sa projection ne lit pas `DELETED`,
`DTSTART` ou `DTEND` directement (`CalendarReadService.kt:181-215`,
`AndroidCalendarProvider.kt:158-197`).

Toute modification/suppression doit relire les marqueurs, l'ID et le calendrier
juste avant l'effet. Un marqueur absent ou douteux interdit l'effet. Une URI
dupliquée bloque la réconciliation et ne doit jamais choisir arbitrairement un
événement. Un événement supprimé devient `MISSING`, sans recréation silencieuse.

## 5. Read-back, réconciliation et liens

Le contrat exige read-back des champs, `DIVERGED` sur différence, puis
`UNKNOWN` et réconciliation après crash/timeout, sans retry aveugle
(`CONTRATS_V63.md:163-166`). **Aucune réconciliation calendrier n'existe
encore.** `ActionDispatcher` fournit un motif utile (journal `PREPARED`,
recherche après interruption, blocage sur `UNKNOWN`), mais pour des effets
locaux d'agent, pas CalendarContract (`ActionDispatcher.kt:304-343`).

`CalendarEventLink` existe dans le contrat mais **pas de classe ni de dépôt**
dans le code Android. `PlanActivity.activityId` est stable ; le lien et son
stockage durable ne le sont pas. Il faut une unicité persistée sur l'URI
canonique par activité et une mutation sérialisée ; le provider Android ne
garantit pas l'unicité de `CUSTOM_APP_URI`.

## 6. Récurrences et DST

La lecture récupère `RRULE` mais ne signale pas explicitement une récurrence et
ne projette pas `RDATE`/`DURATION` (`AndroidCalendarProvider.kt:158-168`).
Premier lot : détecter et signaler les récurrences et exceptions, leur
interdire toute écriture.

Le plan stocke date locale, minute, durée et fuseau IANA ; le moteur résout le
trou DST de printemps à la première heure valide et choisit le premier offset
pour le chevauchement d'automne (`PlanModels.kt:84-95`,
`PlanRevisionEngine.kt:288-307`). L'écriture doit reprendre ces instants,
persister `DTSTART`/`DTEND` en UTC et `EVENT_TIMEZONE` IANA, et vérifier les
deux transitions sur l'appareil.

## 7. Suppression et Etar

Suppression limitée à un lien `CONFIRMED` marqué, après confirmation groupée
portant sur l'ensemble exact ; revalider propriétaire, `calendar_id` et
`event_id` avant chaque effet. Jamais un événement étranger
(`CONTRATS_V63.md:157-170`).

`EtarProbe` vérifie seulement les packages `ws.xsoh.etar` et `.debug` ; cela ne
prouve ni un calendrier Etar inscriptible ni l'affichage de l'événement
(`EtarProbe.kt:9-43`). La visibilité exige d'ouvrir Etar et d'inspecter un
événement synthétique.

## 8. Risques

- Un calendrier Google peut synchroniser insertion, modification et suppression
  vers un compte et d'autres appareils.
- Le provider peut refuser/transformer l'insertion, omettre les marqueurs ou
  retarder le read-back.
- Un crash peut créer un doublon si le retry est aveugle.
- `targetSdk=36` est qualifié sur API 37, mais les comportements runtime
  calendrier restent à tester.
- Titres, comptes et IDs d'événement ne doivent pas finir dans les journaux ou
  preuves.

## 9. Proposition de ticket V63-L4-01

**Statut : `BLOCKED_APPROVAL`.** Exécutable après approbations et dépendances.

**Objectif** : depuis un plan approuvé, créer, déplacer et supprimer des
événements non récurrents dans un calendrier local choisi explicitement ;
conserver un lien durable par activité ; relire le provider après chaque effet ;
réconcilier les interruptions sans doublon ; vérifier la visibilité Etar.

**Dépendances** :
- `V63-L2-02` intégré (permission/consentement lecture) ;
- `V63-L2-03` accepté après lecture réelle Pixel/Etar ;
- `V63-L3-01` Android accepté (activité approuvée, IDs stables) ;
- approbation séparée `WRITE_CALENDAR`, test synthétique sur calendrier local,
  sauvegarde/restauration du calendrier ciblé.

**Scope proposé** :
- `AndroidManifest.xml` : ajout isolé `WRITE_CALENDAR` ;
- `android/.../calendar/write/**` : permission/consentement écriture, choix du
  calendrier, adaptateur CalendarContract, lecture de réconciliation, dépôt
  durable des liens/opérations (`PENDING|CONFIRMED|MISSING|DIVERGED|ORPHANED|
  UNKNOWN`) ;
- intégration limitée à `PlanController.kt`, `PlanScreen.kt`,
  `AtlasApplication.kt` : choix explicite, état, confirmation précise ;
- tests `calendar/write/**` JVM et androidTest.

**Oracle** :
- fake provider JVM : refus/révocation, choix local, URI unique, insertion
  répétée, crash avant/après commit, marqueur absent, URI dupliquée, read-back
  différent → `DIVERGED`, incertain → `UNKNOWN` sans retry, événement supprimé,
  récurrence ignorée, événement étranger jamais modifié, DST ;
- lint/build debug + JVM ciblés liés au SHA final ;
- instrumentation Android 17/API 37 : permissions réelles,
  insert/read-back/update/delete d'un événement synthétique, marqueurs ;
- vérification manuelle Pixel/Etar : visibilité, déplacement, suppression,
  absence de doublon, sauvegarde avant test et état final restauré.

**Arrêts obligatoires** : aucun calendrier visible/local/inscriptible →
`BLOCKED_ENVIRONMENT`, aucun effet ; permission refusée/révoquée, provider
indisponible, marqueur perdu, lecture ambiguë, URI dupliquée, donnée corrompue,
sauvegarde impossible ou récurrence → arrêt. Résultat incertain = `UNKNOWN`,
aucune nouvelle insertion automatique.

## 10. Verdict

**GO_WITH_LIMITATIONS pour préparer le ticket ; BLOCKED pour toute écriture
réelle** tant que les permissions, l'appareil, un calendrier local inscriptible
et la sauvegarde ne sont pas qualifiés. Aucun test exécuté, aucune écriture,
aucun ADB.
