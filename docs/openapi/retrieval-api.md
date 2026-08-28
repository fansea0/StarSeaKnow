# RAG Retrieval API v1

This API is a server-to-server retrieval interface. It searches the complete
knowledge scope authorized for the API Key and returns globally ranked source
chunks. It is not a browser-facing API.

The machine-readable contract is [retrieval-api.yaml](retrieval-api.yaml).

## Endpoint and authentication

`POST /openapi/v1/retrieval` accepts only `application/json` and requires one
`Authorization: Bearer <RAG-API-Key>` header. An optional `X-Request-ID` header
may contain a safe correlation value (letters, numbers, `.`, `_`, or `-`, up to
64 characters). The server returns `X-Request-ID` on every response.

API Key Scope is configured by the tenant administrator and covers the complete
authorized knowledge-base set. The caller neither sends nor controls a
knowledge-base ID, cannot narrow the scope, and cannot expand it. `knowledge_id`
is unsupported. `metadata_condition` is also unsupported. Supplying either one,
or any other unknown field, returns `400 invalid_request`.

Store a newly created or rotated key in a server-side secret manager immediately:
the plaintext is shown once and cannot be recovered. Never put a key in browser
code, mobile applications, source control, logs, tickets, or `X-Request-ID`.

## Transport environments

HTTP is only for trusted-network test credentials, such as a localhost, VPN, or
approved private network endpoint. Do not expose HTTP to the public internet and
do not use live credentials over HTTP. Public or live production traffic must use
HTTPS. This is a server-to-server API; terminate TLS at trusted infrastructure.

Every example below uses the deliberately invalid test key
`rag_test_k_example.example-secret-not-valid`. It can never authenticate.

## Request

The UTF-8 JSON request body is limited to 32 KiB. It has exactly two permitted
fields: required `query` and optional `retrieval_setting`. `query` is trimmed at
its Unicode boundaries and must contain 1–250 characters. The settings object
accepts only `top_k` (integer 1–20, default `5`) and `score_threshold` (number
0–1, default `0`).

```json
{
  "query": "退款需要哪些材料？",
  "retrieval_setting": {
    "top_k": 5,
    "score_threshold": 0.5
  }
}
```

### cURL

```bash
curl -X POST http://127.0.0.1:8090/openapi/v1/retrieval \
  -H 'Authorization: Bearer rag_test_k_example.example-secret-not-valid' \
  -H 'Content-Type: application/json' \
  -d '{"query":"退款需要哪些材料？","retrieval_setting":{"top_k":5,"score_threshold":0.5}}'
```

### Java

```java
var client = java.net.http.HttpClient.newHttpClient();
var request = java.net.http.HttpRequest.newBuilder(
        java.net.URI.create("https://api.example.com/openapi/v1/retrieval"))
    .header("Authorization", "Bearer rag_test_k_example.example-secret-not-valid")
    .header("Content-Type", "application/json")
    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
        "{\"query\":\"退款需要哪些材料？\",\"retrieval_setting\":{\"top_k\":5,\"score_threshold\":0.5}}"))
    .build();
var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
```

### Python

```python
import json
from urllib.request import Request, urlopen

request = Request(
    "https://api.example.com/openapi/v1/retrieval",
    data=json.dumps({"query": "退款需要哪些材料？", "retrieval_setting":
                     {"top_k": 5, "score_threshold": 0.5}}).encode(),
    headers={"Authorization": "Bearer rag_test_k_example.example-secret-not-valid",
             "Content-Type": "application/json"},
    method="POST",
)
with urlopen(request) as response:
    print(response.read().decode())
```

### JavaScript

```javascript
const response = await fetch("https://api.example.com/openapi/v1/retrieval", {
  method: "POST",
  headers: {
    Authorization: "Bearer rag_test_k_example.example-secret-not-valid",
    "Content-Type": "application/json",
  },
  body: JSON.stringify({
    query: "退款需要哪些材料？",
    retrieval_setting: { top_k: 5, score_threshold: 0.5 },
  }),
});
const payload = await response.json();
```

## Success response

`200 OK` returns `application/json`. `records` is present even when no chunks
match, in which case it is `[]`. Each record includes `content`, normalized
`score` (0–1, descending order), `title`, and `metadata` with `document_id`,
`chunk_id`, `file_type`, `page_number`, and `chunk_index`. Metadata is always an
object; individual source values can be `null` when unavailable.

```json
{
  "records": [
    {
      "content": "退款申请需要提交订单号、付款凭证以及退款原因。",
      "score": 0.92,
      "title": "售后服务说明.pdf",
      "metadata": {
        "document_id": "65c28997-ad56-4812-8a50-e00e804b45cc",
        "chunk_id": "a3c05b1e-c40b-4eb6-8a81-26f9874f6f7b",
        "file_type": "pdf",
        "page_number": 3,
        "chunk_index": 12
      }
    }
  ]
}
```

For resource protection, each content value is capped at 8 KiB and the serialized
response at 128 KiB. If the response would exceed its cap, lower-scoring records
are omitted.

Successful responses include:

| Header | Meaning |
|---|---|
| `X-Request-ID` | Request correlation ID. |
| `X-RateLimit-Limit` | Credential limit for the current window. |
| `X-RateLimit-Remaining` | Requests remaining in the current window. |
| `X-RateLimit-Reset` | Window reset as a Unix epoch second. |
| `Cache-Control: no-store` | The response must not be stored by shared caches or browsers. |

## Errors and retries

All error bodies use this stable JSON envelope. `param` is a field path when an
input field is at fault; otherwise it is `null`.

```json
{
  "request_id": "req-safe-01",
  "error": {
    "code": "invalid_request",
    "message": "retrieval_setting.top_k must be between 1 and 20.",
    "param": "retrieval_setting.top_k"
  }
}
```

| Status | Error code | Meaning and action |
|---:|---|---|
| 400 | `invalid_authorization_header`, `https_required`, `invalid_request` | Correct the header, transport, JSON, or request fields. |
| 401 | `missing_authorization_header`, `authentication_failed`, `credential_type_not_supported` | Supply a valid credential. Invalid, expired, and revoked keys all return `authentication_failed`. |
| 403 | `credential_disabled`, `credential_type_not_allowed`, `ip_not_allowed`, `credential_has_no_knowledge_scope` | Disabled credentials return 403; ask the tenant administrator to enable or correctly scope the credential, or update the IP allowlist. |
| 413 | `request_too_large` | Keep the UTF-8 request body at or below 32 KiB. |
| 415 | `invalid_request` | Send `Content-Type: application/json`. |
| 429 | `rate_limit_exceeded` | Wait the `Retry-After` number of seconds before retrying. The response also includes the rate-limit headers. |
| 500 | `internal_error` | Retry only when appropriate for your operation; no internals are exposed. |
| 503 | `retrieval_unavailable` | Retry with bounded exponential backoff. |
| 504 | `retrieval_timeout` | Retry with bounded exponential backoff and a suitable deadline. |

Do not retry 400, 401, 403, 413, or 415 without correcting the cause. Use
`X-Request-ID` when reporting an issue. Treat all response content as sensitive;
successful responses are marked `Cache-Control: no-store`.

## Key rotation

Key rotation issues a new plaintext key once and revokes or expires the previous
key according to the tenant administrator's configured overlap period. Store the
new value in the server-side secret manager before deploying it. Update consumers
one at a time, verify each consumer uses the new key, then allow the old key to
be revoked. If the one-time value is lost, rotate again; it cannot be retrieved.
