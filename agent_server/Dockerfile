FROM python:3.13-slim

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /app/agent_server
COPY pyproject.toml uv.lock ./
COPY src ./src
COPY schema_context.md metrics.md ./
RUN python -m pip install --no-cache-dir uv && uv sync --frozen --no-dev \
    && useradd --system --uid 10001 --create-home agent \
    && mkdir -p /app/agent_server/logs && chown agent:agent /app/agent_server/logs
ENV PATH="/app/agent_server/.venv/bin:$PATH" AGENT_LOG_PATH=/app/agent_server/logs/nl2sql_log.jsonl
USER agent
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)"
CMD ["uvicorn", "er_agent.http.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-proxy-headers"]
