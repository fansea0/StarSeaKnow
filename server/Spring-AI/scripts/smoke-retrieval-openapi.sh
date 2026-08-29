#!/usr/bin/env bash
set -euo pipefail

: "${RAG_API_BASE_URL:?set RAG_API_BASE_URL}"
: "${RAG_API_KEY:?set RAG_API_KEY}"

curl --fail-with-body --silent --show-error \
  -X POST "${RAG_API_BASE_URL}/openapi/v1/retrieval" \
  -H "Authorization: Bearer ${RAG_API_KEY}" \
  -H 'Content-Type: application/json' \
  -d '{"query":"测试检索","retrieval_setting":{"top_k":3,"score_threshold":0.0}}'
