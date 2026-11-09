# Wrapper Docker du Relay local

Cette image ajoute uniquement un healthcheck à `atlas-paseo-relay:r3a`, attendu
avec l'ID `sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889`.
Le healthcheck exécute `/bin/busybox wget -q -O /dev/null http://127.0.0.1:4000/health`. BusyBox vient de
`busybox:1.36.1-musl@sha256:3c6ae8008e2c2eedd141725c30b20d9c36b026eb796688f88205845ef17aa213`.

Construire depuis ce dossier avec `docker build -t atlas-relay-v7-12:local .` après
avoir vérifié l'image locale de base. Le Dockerfile ne publie aucun port et ne
déclare aucun montage ; le réseau et les ports hôte appartiennent à Compose.
