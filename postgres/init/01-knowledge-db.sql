-- Stage 4: a separate database for the AI knowledge base (RAG), owned by ai-service.
--
-- Runs automatically ONLY the first time the postgres container starts with an empty data
-- volume (docker-entrypoint-initdb.d). For an existing volume: docker compose down -v.
--
-- One Postgres server, two databases, two owners:
--   helpdesk  -> helpdesk-api (business data, Flyway migrations)
--   knowledge -> ai-service   (document chunks + embedding vectors)
-- The knowledge user can only connect to its own database.

CREATE USER knowledge WITH PASSWORD 'knowledge';  -- local development credentials only
CREATE DATABASE knowledge OWNER knowledge;

-- Nobody connects to a database unless allowed explicitly (owners always can)
REVOKE CONNECT ON DATABASE knowledge FROM PUBLIC;
REVOKE CONNECT ON DATABASE helpdesk FROM PUBLIC;

-- pgvector: adds the "vector" column type, distance operators (<=>) and HNSW indexes.
-- Creating an extension needs superuser rights, which is why it happens here and not in ai-service.
\connect knowledge
CREATE EXTENSION IF NOT EXISTS vector;
