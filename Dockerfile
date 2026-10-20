FROM python:3.14-slim-bookworm AS python-build
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never
WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
COPY spring_agent ./spring_agent
RUN pip install --no-cache-dir uv==0.11.11 && uv sync --frozen --no-dev

FROM maven:3.9.11-eclipse-temurin-21 AS java-build
WORKDIR /build
COPY gateway/pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY gateway/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS java-runtime

FROM python:3.14-slim-bookworm
ARG APP_VERSION=dev
ENV PATH="/app/.venv/bin:/opt/java/openjdk/bin:${PATH}" \
    JAVA_HOME=/opt/java/openjdk \
    PYTHONUNBUFFERED=1 \
    SPRINGAGENT_CONFIG=/app/config.toml \
    SPRINGAGENT_LOG_STDOUT=1 \
    TIKTOKEN_CACHE_DIR=/app/tiktoken-cache \
    APP_VERSION=${APP_VERSION}
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends libstdc++6 zlib1g \
    && rm -rf /var/lib/apt/lists/*
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
RUN java -version
COPY --from=python-build /app/.venv ./.venv
RUN python -c "import tiktoken; tiktoken.get_encoding('cl100k_base')"
COPY --from=java-build /build/target/*.jar ./gateway/app.jar
COPY spring_agent ./spring_agent
COPY alembic ./alembic
COPY alembic.ini ./
COPY config.toml.example ./config.toml
COPY frontend ./frontend
COPY deploy/engine_entrypoint.py /app/entrypoint.py
COPY deploy/start.py /app/start.py
EXPOSE 15900 16900
HEALTHCHECK --interval=5s --timeout=10s --start-period=60s --retries=30 CMD ["python", "/app/start.py", "--healthcheck"]
ENTRYPOINT ["python", "/app/entrypoint.py"]
CMD ["/bin/sh", "-ec", "alembic upgrade head && exec python /app/start.py"]
