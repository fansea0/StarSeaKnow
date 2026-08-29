package com.starsea.ai.openapi.credential;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.auth.AuthErrorCode;
import com.starsea.ai.auth.AuthException;
import com.starsea.ai.domain.Knowledge;
import com.starsea.ai.mapper.KnowledgeMapper;
import com.starsea.ai.openapi.auth.IpCidrMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ApiCredentialService {

    private static final Logger log = LoggerFactory.getLogger(ApiCredentialService.class);
    private static final String RAG_RETRIEVAL = "RAG_RETRIEVAL";

    private final ApiCredentialMapper credentials;
    private final ApiCredentialKnowledgeMapper credentialKnowledge;
    private final KnowledgeMapper knowledge;
    private final ApiCredentialCache cache;
    private final ApiKeyCodec codec;
    private final int maxKnowledgeBases;

    @Autowired
    public ApiCredentialService(ApiCredentialMapper credentials,
                                ApiCredentialKnowledgeMapper credentialKnowledge,
                                KnowledgeMapper knowledge,
                                ApiCredentialCache cache,
                                ApiKeyCodec codec,
                                ApiKeyProperties properties) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.credentialKnowledge = Objects.requireNonNull(credentialKnowledge, "credentialKnowledge");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.maxKnowledgeBases = properties.getMaxKnowledgeBases();
    }

    ApiCredentialService(ApiCredentialMapper credentials, ApiCredentialKnowledgeMapper credentialKnowledge,
                         KnowledgeMapper knowledge, ApiCredentialCache cache, ApiKeyCodec codec) {
        this(credentials, credentialKnowledge, knowledge, cache, codec, defaultProperties());
    }

    @Transactional
    public CreatedCredential create(CreateCredentialCommand command, AuthContext context) {
        TenantActor actor = requireTenantAdmin(context);
        validateCreate(command);
        Map<UUID, Knowledge> scope = resolveKnowledge(command.knowledgeIds(), actor.tenantId());
        ApiKeyCodec.IssuedKey issued = codec.issue(CredentialType.RAG_RETRIEVAL, command.environment());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        ApiCredential row = new ApiCredential();
        row.setPublicId(UUID.randomUUID());
        row.setTenantId(actor.tenantId());
        row.setCredentialType(RAG_RETRIEVAL);
        row.setName(command.name().trim());
        row.setKeyId(issued.keyId());
        row.setSecretDigest(issued.digest());
        row.setPepperVersion(issued.pepperVersion());
        row.setEnvironment(command.environment());
        row.setStatus("active");
        row.setExpiresAt(toOffsetDateTime(command.expiresAt()));
        row.setAllowedIpCidrs(copyCidrs(command.allowedIpCidrs()));
        row.setRequestsPerMinute(command.requestsPerMinute());
        row.setBurstCapacity(command.burstCapacity());
        row.setMaxConcurrency(command.maxConcurrency());
        row.setAuthorizationVersion(1L);
        row.setDisplayPrefix(issued.displayPrefix());
        row.setDisplayLastFour(issued.displayLastFour());
        row.setCreatedBy(actor.userId());
        row.setCreatedAt(now);
        credentials.insert(row);
        insertScope(row, scope.values(), now);
        evictNowAndAfterCommit(row.getKeyId());
        lifecycleLog("created", actor.tenantId(), row.getPublicId());
        return new CreatedCredential(toView(row, scope.keySet()), issued.rawKey());
    }

    public List<ApiCredentialView> list(AuthContext context) {
        TenantActor actor = requireTenantAdmin(context);
        List<ApiCredential> rows = credentials.selectList(new LambdaQueryWrapper<ApiCredential>()
                .eq(ApiCredential::getTenantId, actor.tenantId())
                .isNull(ApiCredential::getDeletedAt)
                .orderByDesc(ApiCredential::getCreatedAt));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ApiCredentialView> result = new ArrayList<>(rows.size());
        for (ApiCredential row : rows) {
            if (row == null || row.getDeletedAt() != null) {
                continue;
            }
            result.add(toView(row, loadKnowledgePublicIds(row.getId())));
        }
        return List.copyOf(result);
    }

    public ApiCredentialView get(UUID credentialId, AuthContext context) {
        TenantActor actor = requireTenantAdmin(context);
        ApiCredential row = requireCredential(credentialId, actor.tenantId());
        return toView(row, loadKnowledgePublicIds(row.getId()));
    }

    @Transactional
    public ApiCredentialView update(UUID credentialId, UpdateCredentialCommand command, AuthContext context) {
        TenantActor actor = requireTenantAdmin(context);
        if (command == null) {
            throw invalid("credential update is required");
        }
        ApiCredential row = requireCredential(credentialId, actor.tenantId());
        LambdaUpdateWrapper<ApiCredential> update = new LambdaUpdateWrapper<ApiCredential>()
                .eq(ApiCredential::getId, row.getId())
                .eq(ApiCredential::getTenantId, actor.tenantId())
                .isNull(ApiCredential::getDeletedAt)
                .ne(ApiCredential::getStatus, "revoked");
        boolean changed = false;
        if (command.name() != null) {
            validateName(command.name());
            row.setName(command.name().trim());
            update.set(ApiCredential::getName, row.getName());
            changed = true;
        }
        if (command.description() != null) {
            String description = command.description().trim();
            if (description.length() > 512) {
                throw invalid("description must be at most 512 characters");
            }
            row.setDescription(description.isEmpty() ? null : description);
            update.set(ApiCredential::getDescription, row.getDescription());
            changed = true;
        }
        if (command.allowedIpCidrs() != null) {
            validateCidrs(command.allowedIpCidrs());
            row.setAllowedIpCidrs(copyCidrs(command.allowedIpCidrs()));
            update.set(ApiCredential::getAllowedIpCidrs, row.getAllowedIpCidrs());
            changed = true;
        }
        if (command.requestsPerMinute() != null) {
            validateLimit(command.requestsPerMinute(), 100_000, "requestsPerMinute");
            row.setRequestsPerMinute(command.requestsPerMinute());
            update.set(ApiCredential::getRequestsPerMinute, row.getRequestsPerMinute());
            changed = true;
        }
        if (command.burstCapacity() != null) {
            validateLimit(command.burstCapacity(), 100_000, "burstCapacity");
            row.setBurstCapacity(command.burstCapacity());
            update.set(ApiCredential::getBurstCapacity, row.getBurstCapacity());
            changed = true;
        }
        if (command.maxConcurrency() != null) {
            validateLimit(command.maxConcurrency(), 10_000, "maxConcurrency");
            row.setMaxConcurrency(command.maxConcurrency());
            update.set(ApiCredential::getMaxConcurrency, row.getMaxConcurrency());
            changed = true;
        }
        if (command.expiresAtPresent()) {
            row.setExpiresAt(toOffsetDateTime(command.expiresAt()));
            update.set(ApiCredential::getExpiresAt, row.getExpiresAt());
            changed = true;
        }
        if (command.status() != null) {
            updateStatus(row, command.status());
            update.set(ApiCredential::getStatus, row.getStatus());
            changed = true;
        }
        if (changed && credentials.update(null, update) != 1) {
            throw concurrentCredentialChange(credentialId, actor.tenantId());
        }
        evictNowAndAfterCommit(row.getKeyId());
        lifecycleLog("updated", actor.tenantId(), row.getPublicId());
        return toView(row, loadKnowledgePublicIds(row.getId()));
    }

    @Transactional
    public ApiCredentialView replaceKnowledgeBases(UUID credentialId,
                                                   Set<UUID> knowledgeIds,
                                                   AuthContext context) {
        TenantActor actor = requireTenantAdmin(context);
        validateKnowledgeCount(knowledgeIds);
        ApiCredential row = requireCredential(credentialId, actor.tenantId());
        requireRagType(row);
        Map<UUID, Knowledge> scope = resolveKnowledge(knowledgeIds, actor.tenantId());
        credentialKnowledge.delete(new LambdaQueryWrapper<ApiCredentialKnowledge>()
                .eq(ApiCredentialKnowledge::getCredentialId, row.getId())
                .eq(ApiCredentialKnowledge::getTenantId, actor.tenantId()));
        insertScope(row, scope.values(), OffsetDateTime.now(ZoneOffset.UTC));
        Long previousAuthorizationVersion = row.getAuthorizationVersion();
        row.setAuthorizationVersion(nextAuthorizationVersion(previousAuthorizationVersion));
        int affected = credentials.update(null, new LambdaUpdateWrapper<ApiCredential>()
                .eq(ApiCredential::getId, row.getId())
                .eq(ApiCredential::getTenantId, actor.tenantId())
                .isNull(ApiCredential::getDeletedAt)
                .ne(ApiCredential::getStatus, "revoked")
                .eq(previousAuthorizationVersion != null, ApiCredential::getAuthorizationVersion,
                        previousAuthorizationVersion)
                .set(ApiCredential::getAuthorizationVersion, row.getAuthorizationVersion()));
        if (affected != 1) {
            throw concurrentCredentialChange(credentialId, actor.tenantId());
        }
        evictNowAndAfterCommit(row.getKeyId());
        lifecycleLog("scope_replaced", actor.tenantId(), row.getPublicId());
        return toView(row, scope.keySet());
    }

    @Transactional
    public RotatedCredential rotate(UUID credentialId, AuthContext context) {
        TenantActor actor = requireTenantAdmin(context);
        ApiCredential old = requireCredential(credentialId, actor.tenantId());
        requireRagType(old);
        if ("revoked".equals(old.getStatus())) {
            throw invalid("revoked credential cannot be rotated");
        }
        CredentialType type = CredentialType.valueOf(old.getCredentialType());
        ApiKeyCodec.IssuedKey issued = codec.issue(type, old.getEnvironment());
        List<ApiCredentialKnowledge> oldScope = loadScopeLinks(old.getId());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        int revoked = credentials.update(null, new LambdaUpdateWrapper<ApiCredential>()
                .eq(ApiCredential::getId, old.getId())
                .eq(ApiCredential::getTenantId, actor.tenantId())
                .isNull(ApiCredential::getDeletedAt)
                .in(ApiCredential::getStatus, "active", "disabled")
                .eq(ApiCredential::getAuthorizationVersion, old.getAuthorizationVersion())
                .set(ApiCredential::getStatus, "revoked")
                .set(ApiCredential::getRevokedAt, now));
        if (revoked != 1) {
            throw concurrentCredentialChange(credentialId, actor.tenantId());
        }
        old.setStatus("revoked");
        old.setRevokedAt(now);

        ApiCredential replacement = copyForRotation(old, issued, actor.userId(), now);
        credentials.insert(replacement);
        for (ApiCredentialKnowledge oldLink : oldScope) {
            ApiCredentialKnowledge newLink = new ApiCredentialKnowledge();
            newLink.setTenantId(actor.tenantId());
            newLink.setCredentialId(replacement.getId());
            newLink.setCredentialType(replacement.getCredentialType());
            newLink.setKnowledgeId(oldLink.getKnowledgeId());
            newLink.setCreatedAt(now);
            credentialKnowledge.insert(newLink);
        }
        evictNowAndAfterCommit(old.getKeyId(), replacement.getKeyId());
        lifecycleLog("rotated", actor.tenantId(), old.getPublicId());
        lifecycleLog("created_by_rotation", actor.tenantId(), replacement.getPublicId());
        return new RotatedCredential(toView(replacement, publicIdsForLinks(oldScope)), issued.rawKey());
    }

    @Transactional
    public ApiCredentialView revoke(UUID credentialId, AuthContext context) {
        TenantActor actor = requireTenantAdmin(context);
        ApiCredential row = requireCredential(credentialId, actor.tenantId());
        if (!"revoked".equals(row.getStatus())) {
            OffsetDateTime revokedAt = OffsetDateTime.now(ZoneOffset.UTC);
            int affected = credentials.update(null, new LambdaUpdateWrapper<ApiCredential>()
                    .eq(ApiCredential::getId, row.getId())
                    .eq(ApiCredential::getTenantId, actor.tenantId())
                    .isNull(ApiCredential::getDeletedAt)
                    .in(ApiCredential::getStatus, "active", "disabled")
                    .set(ApiCredential::getStatus, "revoked")
                    .set(ApiCredential::getRevokedAt, revokedAt));
            if (affected != 1) {
                ApiCredential current = requireCredential(credentialId, actor.tenantId());
                if (!"revoked".equals(current.getStatus())) {
                    throw invalid("credential changed concurrently");
                }
                row = current;
            } else {
                row.setStatus("revoked");
                row.setRevokedAt(revokedAt);
            }
            lifecycleLog("revoked", actor.tenantId(), row.getPublicId());
        }
        evictNowAndAfterCommit(row.getKeyId());
        return toView(row, loadKnowledgePublicIds(row.getId()));
    }

    @Transactional
    public void delete(UUID credentialId, AuthContext context) {
        TenantActor actor = requireTenantAdmin(context);
        ApiCredential row = requireCredentialIncludingDeleted(credentialId, actor.tenantId());
        if (row.getDeletedAt() == null) {
            OffsetDateTime deletedAt = OffsetDateTime.now(ZoneOffset.UTC);
            int affected = credentials.update(null, new LambdaUpdateWrapper<ApiCredential>()
                    .eq(ApiCredential::getId, row.getId())
                    .eq(ApiCredential::getTenantId, actor.tenantId())
                    .isNull(ApiCredential::getDeletedAt)
                    .set(ApiCredential::getStatus, "revoked")
                    .set(ApiCredential::getRevokedAt, deletedAt)
                    .set(ApiCredential::getDeletedAt, deletedAt));
            if (affected != 1) {
                ApiCredential current = requireCredentialIncludingDeleted(credentialId, actor.tenantId());
                if (current.getDeletedAt() == null) {
                    throw invalid("credential changed concurrently");
                }
                row = current;
            } else {
                row.setStatus("revoked");
                row.setRevokedAt(deletedAt);
                row.setDeletedAt(deletedAt);
            }
            lifecycleLog("deleted", actor.tenantId(), row.getPublicId());
        }
        evictNowAndAfterCommit(row.getKeyId());
    }

    private ApiCredential copyForRotation(ApiCredential old,
                                          ApiKeyCodec.IssuedKey issued,
                                          long createdBy,
                                          OffsetDateTime createdAt) {
        ApiCredential replacement = new ApiCredential();
        replacement.setPublicId(UUID.randomUUID());
        replacement.setTenantId(old.getTenantId());
        replacement.setCredentialType(old.getCredentialType());
        replacement.setName(old.getName());
        replacement.setDescription(old.getDescription());
        replacement.setKeyId(issued.keyId());
        replacement.setSecretDigest(issued.digest());
        replacement.setPepperVersion(issued.pepperVersion());
        replacement.setEnvironment(old.getEnvironment());
        replacement.setStatus("active");
        replacement.setExpiresAt(old.getExpiresAt());
        replacement.setAllowedIpCidrs(copyCidrs(old.getAllowedIpCidrs()));
        replacement.setRequestsPerMinute(old.getRequestsPerMinute());
        replacement.setBurstCapacity(old.getBurstCapacity());
        replacement.setMaxConcurrency(old.getMaxConcurrency());
        replacement.setAuthorizationVersion(old.getAuthorizationVersion());
        replacement.setDisplayPrefix(issued.displayPrefix());
        replacement.setDisplayLastFour(issued.displayLastFour());
        replacement.setRotatedFromId(old.getId());
        replacement.setCreatedBy(createdBy);
        replacement.setCreatedAt(createdAt);
        return replacement;
    }

    private void validateCreate(CreateCredentialCommand command) {
        if (command == null) {
            throw invalid("credential request is required");
        }
        validateName(command.name());
        if (!RAG_RETRIEVAL.equals(command.credentialType())) {
            throw invalid("only RAG_RETRIEVAL credentials are supported in v1");
        }
        if (!"test".equals(command.environment()) && !"live".equals(command.environment())) {
            throw invalid("environment must be test or live");
        }
        validateCidrs(command.allowedIpCidrs());
        validateLimit(command.requestsPerMinute(), 100_000, "requestsPerMinute");
        validateLimit(command.burstCapacity(), 100_000, "burstCapacity");
        validateLimit(command.maxConcurrency(), 10_000, "maxConcurrency");
    }

    private void validateName(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 128) {
            throw invalid("name must be between 1 and 128 characters");
        }
    }

    private void validateLimit(int value, int maximum, String field) {
        if (value < 1 || value > maximum) {
            throw invalid(field + " is outside the supported range");
        }
    }

    private void validateCidrs(List<String> cidrs) {
        if (cidrs == null) {
            return;
        }
        for (String cidr : cidrs) {
            if (!isValidCidr(cidr)) {
                throw invalid("invalid IP CIDR");
            }
        }
    }

    private boolean isValidCidr(String cidr) {
        if (cidr == null) {
            return false;
        }
        int slash = cidr.lastIndexOf('/');
        if (slash <= 0 || slash == cidr.length() - 1) {
            return false;
        }
        String address = cidr.substring(0, slash);
        return IpCidrMatcher.isIpLiteral(address) && IpCidrMatcher.matches(address, cidr);
    }

    private Map<UUID, Knowledge> resolveKnowledge(Set<UUID> publicIds, long tenantId) {
        Set<UUID> requested = publicIds == null ? Set.of() : Set.copyOf(publicIds);
        validateKnowledgeCount(requested);
        if (requested.isEmpty()) {
            return Map.of();
        }
        List<Knowledge> rows = knowledge.selectList(new QueryWrapper<Knowledge>()
                .in("public_id", requested)
                .eq("tenant_id", tenantId));
        Map<UUID, Knowledge> resolved = new LinkedHashMap<>();
        if (rows != null) {
            for (Knowledge row : rows) {
                if (row != null && row.getPublicId() != null) {
                    resolved.put(row.getPublicId(), row);
                }
            }
        }
        if (!resolved.keySet().equals(requested)) {
            log.warn("api_credential_scope_rejected action=knowledge_resolution tenantId={}", tenantId);
            throw new AuthException(AuthErrorCode.CROSS_TENANT,
                    "one or more knowledge bases are outside the authenticated tenant");
        }
        return resolved;
    }

    private void validateKnowledgeCount(Set<UUID> publicIds) {
        if (publicIds != null && Set.copyOf(publicIds).size() > maxKnowledgeBases) {
            throw invalid("knowledgeIds supports at most " + maxKnowledgeBases + " distinct values");
        }
    }

    private ApiCredential requireCredential(UUID publicId, long tenantId) {
        ApiCredential row = requireCredentialIncludingDeleted(publicId, tenantId);
        if (row.getDeletedAt() != null) {
            throw new AuthException(AuthErrorCode.CROSS_TENANT,
                    "credential is outside the authenticated tenant");
        }
        return row;
    }

    private ApiCredential requireCredentialIncludingDeleted(UUID publicId, long tenantId) {
        if (publicId == null) {
            throw invalid("credential id is required");
        }
        ApiCredential row = credentials.selectOne(new LambdaQueryWrapper<ApiCredential>()
                .eq(ApiCredential::getPublicId, publicId)
                .eq(ApiCredential::getTenantId, tenantId));
        if (row == null) {
            throw new AuthException(AuthErrorCode.CROSS_TENANT,
                    "credential is outside the authenticated tenant");
        }
        return row;
    }

    private void requireRagType(ApiCredential row) {
        if (!RAG_RETRIEVAL.equals(row.getCredentialType())) {
            throw invalid("credential does not support RAG knowledge scope");
        }
    }

    private void updateStatus(ApiCredential row, String requestedStatus) {
        if (!"active".equals(requestedStatus) && !"disabled".equals(requestedStatus)) {
            throw invalid("status must be active or disabled");
        }
        if ("revoked".equals(row.getStatus())) {
            throw invalid("revoked credential cannot be restored");
        }
        if (!"active".equals(row.getStatus()) && !"disabled".equals(row.getStatus())) {
            throw invalid("credential status transition is not allowed");
        }
        row.setStatus(requestedStatus);
    }

    private AuthException concurrentCredentialChange(UUID publicId, long tenantId) {
        ApiCredential current = requireCredential(publicId, tenantId);
        if ("revoked".equals(current.getStatus())) {
            return invalid("revoked credential cannot be modified");
        }
        return invalid("credential changed concurrently");
    }

    private TenantActor requireTenantAdmin(AuthContext context) {
        if (context == null || context.getKind() != AuthContext.Kind.BUSINESS
                || context.getTenantId() == null || !"tenant_admin".equals(context.getRole())) {
            throw new AuthException(AuthErrorCode.FORBIDDEN_ROLE, "tenant administrator required");
        }
        return new TenantActor(context.getUserId(), context.getTenantId());
    }

    private List<ApiCredentialKnowledge> loadScopeLinks(Long credentialId) {
        List<ApiCredentialKnowledge> links = credentialKnowledge.selectList(
                new LambdaQueryWrapper<ApiCredentialKnowledge>()
                        .eq(ApiCredentialKnowledge::getCredentialId, credentialId));
        return links == null ? List.of() : List.copyOf(links);
    }

    private Set<UUID> loadKnowledgePublicIds(Long credentialId) {
        return publicIdsForLinks(loadScopeLinks(credentialId));
    }

    private Set<UUID> publicIdsForLinks(List<ApiCredentialKnowledge> links) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ApiCredentialKnowledge link : links) {
            if (link.getKnowledgeId() != null) {
                ids.add(link.getKnowledgeId());
            }
        }
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<Knowledge> rows = knowledge.selectBatchIds(ids);
        Set<UUID> publicIds = new LinkedHashSet<>();
        if (rows != null) {
            for (Knowledge row : rows) {
                if (row != null && row.getPublicId() != null) {
                    publicIds.add(row.getPublicId());
                }
            }
        }
        return Set.copyOf(publicIds);
    }

    private void insertScope(ApiCredential credential,
                             Collection<Knowledge> scope,
                             OffsetDateTime createdAt) {
        for (Knowledge knowledgeRow : scope) {
            ApiCredentialKnowledge link = new ApiCredentialKnowledge();
            link.setTenantId(credential.getTenantId());
            link.setCredentialId(credential.getId());
            link.setCredentialType(credential.getCredentialType());
            link.setKnowledgeId(knowledgeRow.getId());
            link.setCreatedAt(createdAt);
            credentialKnowledge.insert(link);
        }
    }

    private ApiCredentialView toView(ApiCredential row, Set<UUID> knowledgeIds) {
        return new ApiCredentialView(
                row.getPublicId(), row.getName(), row.getCredentialType(), row.getDescription(),
                row.getEnvironment(), row.getStatus(), toInstant(row.getExpiresAt()), copyCidrs(row.getAllowedIpCidrs()),
                row.getRequestsPerMinute(), row.getBurstCapacity(), row.getMaxConcurrency(),
                row.getAuthorizationVersion(), row.getDisplayPrefix(), row.getDisplayLastFour(),
                toInstant(row.getCreatedAt()), toInstant(row.getRevokedAt()), toInstant(row.getLastUsedAt()),
                row.getLastUsedIp(), knowledgeIds == null ? Set.of() : Set.copyOf(knowledgeIds));
    }

    private List<String> copyCidrs(List<String> cidrs) {
        return cidrs == null ? List.of() : List.copyOf(cidrs);
    }

    private long nextAuthorizationVersion(Long current) {
        return current == null ? 1L : Math.addExact(current, 1L);
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private AuthException invalid(String message) {
        return new AuthException(AuthErrorCode.REGISTRATION_INVALID, message);
    }

    private static ApiKeyProperties defaultProperties() {
        return new ApiKeyProperties();
    }

    private void evictNowAndAfterCommit(String... keyIds) {
        List<String> keys = java.util.Arrays.stream(keyIds)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // A rollback may cause an extra database reload, but must never restore a possibly stale cache entry.
        keys.forEach(cache::evict);
        if (keys.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                keys.forEach(cache::evict);
            }
        });
    }

    private void lifecycleLog(String action, long tenantId, UUID credentialPublicId) {
        log.info("api_credential_lifecycle action={} tenantId={} credentialPublicId={}",
                action, tenantId, credentialPublicId);
    }

    private record TenantActor(long userId, long tenantId) {
    }

    public record CreateCredentialCommand(
            String name, String credentialType, String environment,
            Set<UUID> knowledgeIds, List<String> allowedIpCidrs,
            int requestsPerMinute, int burstCapacity, int maxConcurrency,
            Instant expiresAt) {
    }

    public record UpdateCredentialCommand(
            String name, String description, List<String> allowedIpCidrs,
            Integer requestsPerMinute, Integer burstCapacity, Integer maxConcurrency,
            boolean expiresAtPresent, Instant expiresAt, String status) {
    }

    public record ApiCredentialView(
            UUID id, String name, String credentialType, String description,
            String environment, String status, Instant expiresAt,
            List<String> allowedIpCidrs, Integer requestsPerMinute,
            Integer burstCapacity, Integer maxConcurrency, Long authorizationVersion,
            String displayPrefix, String displayLastFour, Instant createdAt,
            Instant revokedAt, Instant lastUsedAt, String lastUsedIp,
            Set<UUID> knowledgeIds) {
        public ApiCredentialView {
            allowedIpCidrs = allowedIpCidrs == null ? List.of() : List.copyOf(allowedIpCidrs);
            knowledgeIds = knowledgeIds == null ? Set.of() : Set.copyOf(knowledgeIds);
        }
    }

    public record CreatedCredential(ApiCredentialView credential, String apiKey) {
    }

    public record RotatedCredential(ApiCredentialView credential, String apiKey) {
    }
}
