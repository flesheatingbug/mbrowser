FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    HOME=/config \
    CONFIG_DIR=/config \
    PORT=4242

# tzdata: so that TZ=Europe/Amsterdam (and so on) works; util-linux: setpriv, used to drop root like linuxserver.io images do
RUN apt-get update \
 && apt-get install -y --no-install-recommends tzdata util-linux \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/mbrowser
COPY app ./app
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 4242
VOLUME /config
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
