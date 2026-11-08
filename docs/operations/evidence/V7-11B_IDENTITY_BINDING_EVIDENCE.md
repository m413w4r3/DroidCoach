# V7-11B-B2 — Preuve lecture seule identité et binding

**Date :** 2026-11-08 · **BASE_SHA :** `9385d1249ffdda533e16e44684d42a5000a6748b` · **HEAD contrôlé :** `9385d1249ffdda533e16e44684d42a5000a6748b`
**Périmètre :** Core Docker live en SQLite `mode=ro`, métadonnées/structure du connecteur sous `~/.local/state/atlas-relay`. Aucun ADB, aucune action sur Pixel, aucun redémarrage.

## Verdict

**BLOCKED pour confirmer le binding V4 de `pixel-v7`.** Docker et SQLite live sont accessibles et intègres, mais la base est au `user_version=3` : les tables `principals_v4`, `credentials_v4`, `principal_capabilities_v4` et `relay_principal_bindings_v4` sont absentes. `agent_proposals_v4` existe, mais ne possède pas la colonne `device_ref`. Les comptes d’identité/binding et les états LEGACY sont donc `UNKNOWN`, pas zéro.

Le registre local du connecteur contient le libellé `pixel-v7`, mais le lien avec Core ne peut pas être établi sans la table de bindings. Le champ `server_id` n’est pas enregistré dans `identity.json`; la valeur runtime ou un éventuel override n’a pas été inspecté. La cohérence avec le Relay live reste `UNKNOWN`.

## Résultats

| Oracle | Observation expurgée | État |
|---|---|---|
| HEAD = BASE_SHA | SHA identiques : `9385d1249ffdda533e16e44684d42a5000a6748b` | PASS |
| Accès Core | `docker exec ... python -c 'print("ok")'` a répondu `ok` | PASS |
| SQLite live | `user_version=3`; `integrity_check=ok` | PASS |
| Tables V4 | `principals_v4`: absente; `credentials_v4`: absente; `principal_capabilities_v4`: absente; `relay_principal_bindings_v4`: absente; `agent_proposals_v4`: présente | BLOCKED — schéma incomplet pour cet audit |
| Principals par kind/state | Non calculable, table absente | UNKNOWN |
| Credentials par audience/state | Non calculable, table absente | UNKNOWN |
| Bindings | Non calculable, table absente | UNKNOWN |
| Capabilities | Non calculable, table absente | UNKNOWN |
| Propositions | 3 lignes dans `agent_proposals_v4`; colonne `device_ref` absente, séparation NULL/non-NULL impossible | UNKNOWN |
| Auth Core | `ATLAS_V4_AUTH_MODE` et `ATLAS_V4_ENABLED` sont unset dans le conteneur. Le code Core utilise `legacy` par défaut pour `ATLAS_V4_AUTH_MODE`; le code Core ne consomme pas `ATLAS_V4_ENABLED`. | PASS pour la valeur par défaut `legacy`; V4_ENABLED unset |
| Credential LEGACY | Non calculable, `credentials_v4` absente | UNKNOWN |
| Connecteur `identity.json` | Présent, fichier régulier `0600`, schéma 1; clés de premier niveau `fingerprint`, `public_key`, `schema_version`, `secret_key`; clé publique de 32 octets et empreinte SHA-256 cohérente | PASS |
| Connecteur `devices.json` | Présent, fichier régulier `0600`, schéma 1, 20 entrées; clés `devices`, `schema_version`; libellé `pixel-v7` présent (booléen) | PASS |
| Connecteur `operations.json` | Présent, fichier régulier `0600`, schéma 1, 9 entrées; clés `entries`, `schema_version` | PASS |
| `server_id` et Relay | Aucun champ `server_id` dans `identity.json`; la valeur runtime/override et la vue live Relay ne sont pas établies dans ce périmètre | UNKNOWN |
| Comparaison connecteur/Core | UNKNOWN : l’ID interne du registre n’a pas de table `relay_principal_bindings_v4` à comparer | UNKNOWN |
| Binding pixel-v7 → principal RELAY → propositions | Le libellé connecteur existe, mais Core ne possède ni table de bindings ni table de principals/credentials; ownership des propositions non vérifiable. Dans le schéma du dépôt, l’équivalent RELAY est l’audience du credential (les kinds de principal ne contiennent pas `RELAY`). | UNKNOWN |
| Non-mutation SQLite | Taille `249856` octets avant/après; `mtime_ns=1794045949210002083` avant/après | PASS |
| Non-mutation connecteur | Pour chaque fichier, taille, mtime et mode identiques avant/après (`0600`) | PASS |

### Métadonnées de fichiers connecteur

Les horodatages sont des nanosecondes Unix; aucun contenu de fichier n’est inclus.

| Fichier | Taille avant/après | mtime_ns avant/après | Mode avant/après | Inchangé |
|---|---:|---:|---:|---|
| `identity.json` | 239 / 239 | 1793054060518239624 / 1793054060518239624 | 0600 / 0600 | oui |
| `devices.json` | 9370 / 9370 | 1793610251130415843 / 1793610251130415843 | 0600 / 0600 | oui |
| `operations.json` | 4419 / 4419 | 1793912825677328286 / 1793912825677328286 | 0600 / 0600 | oui |

## Commandes de preuve

Les scripts ci-dessous émettent uniquement des booléens, noms de schéma, compteurs et métadonnées. Aucun identifiant de ligne, libellé complet, bearer, hash de token ou valeur de clé n’est écrit dans la preuve.

Pré-vol et test d’accès :

```sh
git rev-parse HEAD
git status --short
docker exec atlas-core-atlas-core-1 python -c 'print("ok")'
```

Agrégats Core et statistiques du fichier SQLite :

```sh
docker exec -i atlas-core-atlas-core-1 python - <<'PY'
import json, os, sqlite3
from datetime import datetime, timezone
path='/data/atlas.sqlite3'
def statdb():
    s=os.stat(path)
    return {'size':s.st_size,'mtime_ns':s.st_mtime_ns,
            'mtime_utc':datetime.fromtimestamp(s.st_mtime,timezone.utc).isoformat()}
out={}
out['stat_before']=statdb()
c=sqlite3.connect('file:/data/atlas.sqlite3?mode=ro',uri=True)
c.row_factory=sqlite3.Row
out['user_version']=c.execute('PRAGMA user_version').fetchone()[0]
out['integrity_ok']=all(r[0]=='ok' for r in c.execute('PRAGMA integrity_check'))
tables={r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
required=('principals_v4','credentials_v4','principal_capabilities_v4',
          'relay_principal_bindings_v4','agent_proposals_v4')
out['tables']={t:t in tables for t in required}
def grouped(table,cols):
    if table not in tables:return None
    names=cols.split(',')
    sql='SELECT '+','.join(names)+',COUNT(*) AS n FROM '+table
    sql+=' GROUP BY '+','.join(names)+' ORDER BY '+','.join(names)
    return [{**{x:r[x] for x in names},'count':r['n']} for r in c.execute(sql)]
out['principals_by_kind_state']=grouped('principals_v4','kind,state')
out['credentials_by_audience_state']=grouped('credentials_v4','audience,state')
out['bindings_by_state']=grouped('relay_principal_bindings_v4','state')
out['binding_count']=(c.execute('SELECT COUNT(*) FROM relay_principal_bindings_v4').fetchone()[0]
                      if 'relay_principal_bindings_v4' in tables else None)
if 'principal_capabilities_v4' in tables:
    r=c.execute('SELECT COUNT(*),COALESCE(SUM(granted=1),0),'
                'COALESCE(SUM(granted=0),0) FROM principal_capabilities_v4').fetchone()
    out['capabilities']={'total':r[0],'granted':r[1],'not_granted':r[2]}
else:out['capabilities']=None
if 'agent_proposals_v4' in tables:
    cols={r[1] for r in c.execute('PRAGMA table_info(agent_proposals_v4)')}
    out['proposal_count']=c.execute('SELECT COUNT(*) FROM agent_proposals_v4').fetchone()[0]
    out['proposal_device_ref_column_present']='device_ref' in cols
    if 'device_ref' in cols:
        r=c.execute('SELECT COALESCE(SUM(device_ref IS NOT NULL),0),'
                    'COALESCE(SUM(device_ref IS NULL),0) FROM agent_proposals_v4').fetchone()
        out['proposals']={'device_ref_non_null':r[0],'device_ref_null':r[1]}
    else:out['proposals']=None
else:
    out['proposal_count']=None
    out['proposal_device_ref_column_present']=False
    out['proposals']=None
if 'credentials_v4' in tables:
    out['legacy_active_revoked']={s:c.execute(
        "SELECT COUNT(*) FROM credentials_v4 WHERE audience='LEGACY' AND state=?",(s,)
    ).fetchone()[0] for s in ('ACTIVE','REVOKED')}
else:out['legacy_active_revoked']=None
out['stat_after']=statdb()
c.close()
print(json.dumps(out,separators=(',',':')))
PY
```

Schéma sans lecture de lignes de données :

```sh
docker exec -i atlas-core-atlas-core-1 python - <<'PY'
import json, sqlite3
c=sqlite3.connect('file:/data/atlas.sqlite3?mode=ro',uri=True)
required=('principals_v4','credentials_v4','principal_capabilities_v4',
          'relay_principal_bindings_v4','agent_proposals_v4')
tables={r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
out={'user_version':c.execute('PRAGMA user_version').fetchone()[0],
     'integrity_ok':all(r[0]=='ok' for r in c.execute('PRAGMA integrity_check')),
     'tables':{t:t in tables for t in required},
     'columns':{t:[r[1] for r in c.execute('PRAGMA table_info('+t+')')] if t in tables else None
                for t in required}}
c.close()
print(json.dumps(out,separators=(',',':')))
PY
```

Seules ces deux variables Core ont été interrogées :

```sh
docker exec atlas-core-atlas-core-1 printenv ATLAS_V4_AUTH_MODE
docker exec atlas-core-atlas-core-1 printenv ATLAS_V4_ENABLED
```

Inspection connecteur en mémoire, sans afficher les valeurs JSON :

```sh
python3 - <<'PY'
import base64, hashlib, json, os, re, stat
from pathlib import Path
root=Path.home()/'.local/state/atlas-relay'
names=('identity.json','devices.json','operations.json')
def info(path):
    try:
        s=path.lstat()
        return {'present':True,'regular':stat.S_ISREG(s.st_mode),
                'mode':format(stat.S_IMODE(s.st_mode),'04o'),
                'size':s.st_size,'mtime_ns':s.st_mtime_ns}
    except FileNotFoundError:return {'present':False}
    except Exception:return {'present':'UNKNOWN'}
def read_json(path):
    try:
        fd=os.open(path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0))
        with os.fdopen(fd,'rb') as f:raw=f.read()
        return json.loads(raw.decode('utf-8')),True
    except FileNotFoundError:return None,False
    except Exception:return None,False
def keys(obj):
    if not isinstance(obj,dict):return None
    result=sorted(obj.keys())
    return result if len(result)<=64 and all(
        isinstance(x,str) and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,63}',x)
        for x in result) else 'INVALID_KEYS_SUPPRESSED'
def schema(obj):
    if not isinstance(obj,dict):return None
    v=obj.get('schema_version')
    return v if type(v) is int else 'INVALID_OR_ABSENT'
out={}
for name in names:
    path=root/name
    before=info(path);doc,readable=read_json(path);after=info(path)
    entry={'present':before.get('present'),'regular':before.get('regular'),
           'mode':before.get('mode'),'top_level_keys':keys(doc),
           'schema_version':schema(doc),
           'stat_before':{k:before.get(k) for k in ('size','mtime_ns','mode')},
           'stat_after':{k:after.get(k) for k in ('size','mtime_ns','mode')},
           'stat_unchanged':all(before.get(k)==after.get(k)
                                for k in ('present','regular','mode','size','mtime_ns'))}
    if name=='identity.json':
        valid=False
        if isinstance(doc,dict) and isinstance(doc.get('public_key'),str) and isinstance(doc.get('fingerprint'),str):
            try:
                public=base64.b64decode(doc['public_key'],validate=True)
                valid=(len(public)==32 and len(doc['fingerprint'])==64 and
                       doc['fingerprint']==hashlib.sha256(public).hexdigest())
            except Exception:valid=False
        entry['structure_valid']=bool(readable and isinstance(doc,dict) and
                                      type(doc.get('schema_version')) is int)
        entry['public_key_32_fingerprint_sha256_coherent']=valid
        entry['server_id_field_present']=bool(isinstance(doc,dict) and 'server_id' in doc)
    elif name=='devices.json':
        rows=doc.get('devices') if isinstance(doc,dict) and isinstance(doc.get('devices'),list) else None
        entry['structure_valid']=bool(readable and isinstance(doc,dict) and
                                      type(doc.get('schema_version')) is int and rows is not None)
        entry['count']=len(rows) if rows is not None else None
        entry['pixel_v7_label_present']=(any(isinstance(x,dict) and x.get('label')=='pixel-v7'
                                             for x in rows) if rows is not None else None)
    else:
        rows=doc.get('entries') if isinstance(doc,dict) and isinstance(doc.get('entries'),list) else None
        entry['structure_valid']=bool(readable and isinstance(doc,dict) and
                                      type(doc.get('schema_version')) is int and rows is not None)
        entry['count']=len(rows) if rows is not None else None
    out[name]=entry
print(json.dumps(out,sort_keys=True,separators=(',',':')))
PY
```

Une première agrégation a signalé `OperationalError`; le schéma read-only a ensuite confirmé que `device_ref` n’existe pas sur `agent_proposals_v4`. L’agrégation finale ci-dessus traite explicitement cette colonne absente et ne l’interprète pas comme un compte nul.

## Limites et non-mutation

- Les comptes principals, credentials, bindings, capabilities et credentials LEGACY ne sont pas disponibles dans ce Core live, les tables correspondantes étant absentes.
- Le nombre de propositions avec/sans `device_ref` est inconnu; le schéma ne contient pas cette colonne. Seul le total de 3 propositions est établi.
- La comparaison mémoire des identifiants de registre avec les bindings Core est impossible en l’absence de `relay_principal_bindings_v4`; aucun identifiant n’a été imprimé.
- L’identité connecteur ne persiste pas `server_id`. Le champ de configuration runtime, un éventuel override et une interrogation Relay n’ont pas été lus.
- Le snapshot n’a pas été ouvert par B2 et a été supprimé par le parent après l’audit.
- Le mode SQLite utilisé est `file:/data/atlas.sqlite3?mode=ro`; seules des requêtes `SELECT`, `PRAGMA user_version`, `PRAGMA integrity_check` et `PRAGMA table_info` ont été lancées. Aucun `UPDATE`, `INSERT`, `DELETE` ni PRAGMA modificateur.
- Aucun secret, bearer, hash de token, valeur de clé, ID brut, `device_ref` ou contenu personnel n’est présent dans cette preuve.

**Verdict final : BLOCKED pour l’acceptation identité/binding V4**; intégrité Core, structure connecteur et non-mutation observées PASS, mais le schéma live ne permet pas d’établir l’association demandée.
