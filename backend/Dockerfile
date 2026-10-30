FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    ATLAS_DB_PATH=/data/atlas.sqlite3 \
    ATLAS_AGENT_ADAPTER=mock

WORKDIR /app

COPY requirements.txt ./requirements.txt
RUN pip install --no-cache-dir --disable-pip-version-check -r requirements.txt \
    && mkdir -p /data \
    && chown 10001:10001 /data

COPY --chown=10001:10001 atlas_backend ./backend/atlas_backend
COPY --from=contracts --chown=10001:10001 . ./contracts
COPY --chown=10001:10001 docker-entrypoint.py /usr/local/bin/atlas-core-entrypoint.py

WORKDIR /app/backend

USER 10001:10001

ENTRYPOINT ["python", "/usr/local/bin/atlas-core-entrypoint.py"]
CMD ["uvicorn", "atlas_backend.main:app", "--host", "127.0.0.1", "--port", "8100"]
