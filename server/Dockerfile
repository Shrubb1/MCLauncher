FROM python:3.12-slim

WORKDIR /app
COPY relay.py .
COPY update.zip .
COPY version.txt .
COPY route.json .

ENV PORT=8080 \
    RELAY_DB=/data/relay.db

EXPOSE 8080
CMD ["python", "relay.py"]
