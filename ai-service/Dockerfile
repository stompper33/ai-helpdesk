FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# Dependencies first, so code changes don't reinstall them
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY app ./app
COPY knowledge ./knowledge

RUN useradd --system --uid 10001 app
USER app

EXPOSE 8000
# Config (OPENAI_API_KEY etc.) comes from environment variables set by docker-compose,
# never from a .env file baked into the image (.dockerignore excludes it).
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
