# V7-06A-TRACE-PC — Instrumentation bornée du connecteur

Date : 2026-10-31
Base : `c1c0c56bd5547bc7503118cd6b5ccaf2c2a306cf`
Commit : `c24b1bfcee7ccb4fc54cf4fea8af43a0643ff64b`
Intégration : merge `1bffa9e`
Session : `2b14b7bc-cebc-4ead-94dd-55bd74f7ea8c` — `codex/gpt-6-luna`, `xhigh`
Worktree : `/tmp/atlas-v7-06a-trace-pc`

## Livré

- `integrations/relay/pc/src/diagnostics.ts` : trace mémoire bornée (512 événements),
  désactivée par défaut, activée seulement par `ATLAS_DIAG_TRACE=1`.
- Événements bornés : `operation_received`, `backend_execute_start/end`,
  `core_post_start/end`, `journal_persist_start/end`, `response_ready`,
  `ws_send_attempt/result`, `session_active/session_changed`, `replay`, `uncertain`.
- Champs : horodatage, ordinal de session monotone, empreinte SHA-256 tronquée du
  `request_id` (8 hex), opération fermée, étape, résultat, statut HTTP, code de
  résultat borné, durée. Aucun payload, bearer, token, UUID réel, deviceId brut.
- Mesure séparée : POST Core, chaque persistance de journal, attente totale jusqu’à la
  réponse prête, résultat de l’envoi WS.
- `SessionManager`/`PeerSession` : ordinal de session, trace de réception, de réponse
  prête, de tentative et de résultat d’envoi ; le repli d’erreur après un envoi de
  réponse rejeté n’écrase pas la trace de ce rejet.
- Aucun changement du journal, de la rédaction V4, des règles de replay, des timeouts
  ni des réponses.

## Vérifications

- `npm run build` : PASS.
- Tests ciblés diagnostics/operations/session : 37/37 PASS.
- `npm run test:unit` : **100/100 PASS** (17 fichiers) sur le SHA intégré.
- `git diff --check` : PASS.
- Worktree propre après commit ; `node_modules` non suivi, non commité.

## Extrait synthétique expurgé (durées indicatives)

```json
{"sessionOrdinal":1,"correlationTag":"371d09f8","operation":"v4.consent.state.upsert","stage":"core_post_end","result":"success","httpStatus":200,"resultCode":"ACCEPTED","durationMs":4}
{"sessionOrdinal":1,"correlationTag":"371d09f8","operation":"v4.consent.state.upsert","stage":"journal_persist_end","result":"completed","resultCode":"PERSISTED","durationMs":2}
{"sessionOrdinal":1,"correlationTag":"371d09f8","operation":"v4.consent.state.upsert","stage":"response_ready","result":"ready","resultCode":"OK","durationMs":9}
{"sessionOrdinal":1,"correlationTag":"371d09f8","operation":"v4.consent.state.upsert","stage":"ws_send_result","result":"rejected","resultCode":"SEND_FAILED","httpStatus":null,"durationMs":null}
```

Ce cas reproduit la classe de défaillance V7-05 : Core a répondu et le journal est
`completed_redacted`, mais l’envoi WS de la réponse est rejeté. La trace identifie la
dernière étape (`ws_send_result=rejected`) sans exposer le reçu ni le payload.

## Limites

- `socket.send` accepté ne prouve pas la réception par Android.
- La trace est en mémoire et best-effort ; elle ne remplace pas une preuve de livraison.
- Aucune correction de comportement n’est incluse dans ce ticket.
