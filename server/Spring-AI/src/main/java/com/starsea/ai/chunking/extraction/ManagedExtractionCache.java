package com.starsea.ai.chunking.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.domain.FileTextExtraction;
import com.starsea.ai.mapper.FileTextExtractionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class ManagedExtractionCache {

    private static final Logger log = LoggerFactory.getLogger(ManagedExtractionCache.class);
    private final FileTextExtractionMapper mapper;
    private final DocumentTextExtractorRegistry registry;
    private final ObjectMapper objectMapper;
    private final Path root;

    @Autowired
    public ManagedExtractionCache(FileTextExtractionMapper mapper,
                                  DocumentTextExtractorRegistry registry,
                                  ObjectMapper objectMapper,
                                  @Value("${chunking.extraction.cache-root:${user.dir}/data/extraction-cache}") String root) {
        this(mapper, registry, objectMapper, Path.of(root));
    }

    public ManagedExtractionCache(FileTextExtractionMapper mapper,
                                  DocumentTextExtractorRegistry registry,
                                  ObjectMapper objectMapper,
                                  Path root) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public synchronized ExtractedText getOrExtract(long tenantId, long fileId, String sourceHash,
                                                   Path source, String suppliedType) {
        requirePositive(tenantId, "tenantId");
        requirePositive(fileId, "fileId");
        if (sourceHash == null || !sourceHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("sourceHash must be a SHA-256 hex digest");
        }
        ExtractionCapability capability = registry.probe(source, suppliedType);
        if (!capability.available()) {
            throw new DocumentTextExtractor.ExtractionException(
                    DocumentTextExtractor.FailureReason.UNSUPPORTED, capability.reason());
        }
        FileTextExtraction existing = mapper.findScoped(tenantId, fileId);
        if (existing != null) {
            validateManagedPaths(existing, tenantId, fileId);
        }
        ExtractedText hit = readValid(existing, tenantId, fileId, sourceHash, capability);
        if (hit != null) {
            return hit;
        }

        ExtractedText extracted = registry.extract(source, capability);
        Path directory = scopedDirectory(tenantId, fileId);
        String generation = UUID.randomUUID().toString();
        Path textPath = checked(directory.resolve("text-" + generation + ".txt"), directory);
        Path mapPath = checked(directory.resolve("source-map-" + generation + ".json"), directory);
        writePairAtomically(directory, textPath, mapPath, extracted, sourceHash);

        FileTextExtraction replacement = metadata(tenantId, fileId, sourceHash, extracted,
                textPath, mapPath);
        try {
            int changed = existing == null ? mapper.insert(replacement) : mapper.updateScoped(replacement);
            if (changed != 1) {
                throw new IllegalStateException("Extraction cache metadata was not saved");
            }
        } catch (RuntimeException failure) {
            deleteNewPair(textPath, mapPath, tenantId, fileId, failure);
            throw failure;
        }
        if (existing != null) {
            deleteExistingPair(existing, tenantId, fileId);
        }
        return extracted;
    }

    public synchronized void deleteManagedFiles(long tenantId, long fileId) {
        FileTextExtraction existing = mapper.findScoped(tenantId, fileId);
        if (existing != null) {
            deleteExistingPair(existing, tenantId, fileId);
        }
    }

    private ExtractedText readValid(FileTextExtraction row, long tenantId, long fileId,
                                    String hash, ExtractionCapability capability) {
        if (row == null || !Objects.equals(row.getTenantId(), tenantId)
                || !Objects.equals(row.getFileId(), fileId)
                || !hash.equals(row.getSourceHash())
                || !capability.extractorId().equals(row.getExtractorId())
                || !capability.extractorVersion().equals(row.getExtractorVersion())
                || !capability.detectedMediaType().equals(row.getMediaType())) {
            return null;
        }
        Path directory = scopedDirectory(tenantId, fileId);
        Path textPath = checked(Path.of(row.getManagedTextPath()), directory);
        Path mapPath = checked(Path.of(row.getSourceMapPath()), directory);
        if (!Files.isRegularFile(textPath) || !Files.isRegularFile(mapPath)) {
            return null;
        }
        try {
            long maximumUtf8Bytes = Math.multiplyExact(row.getCharacterCount(), 4L);
            if (Files.size(textPath) > maximumUtf8Bytes) {
                return null;
            }
            String text = Files.readString(textPath, StandardCharsets.UTF_8);
            long count = text.codePointCount(0, text.length());
            if (!Objects.equals(row.getCharacterCount(), count)) {
                return null;
            }
            CacheSidecar sidecar = objectMapper.readValue(mapPath.toFile(), CacheSidecar.class);
            if (!row.getSourceHash().equals(sidecar.sourceHash())
                    || !row.getExtractorId().equals(sidecar.extractorId())
                    || !row.getExtractorVersion().equals(sidecar.extractorVersion())
                    || !row.getMediaType().equals(sidecar.mediaType())
                    || !sha256(text).equals(sidecar.textHash())) {
                return null;
            }
            if (sidecar.sourceSpans().stream().anyMatch(span -> span.textEnd() > text.length())) {
                return null;
            }
            return new ExtractedText(text, row.getMediaType(), row.getExtractorId(),
                    row.getExtractorVersion(), sidecar.sourceSpans(), sidecar.metadata());
        } catch (IOException exception) {
            return null;
        }
    }

    private void writePairAtomically(Path directory, Path textPath, Path mapPath,
                                     ExtractedText extracted, String sourceHash) {
        Path textTemp = null;
        Path mapTemp = null;
        boolean textCommitted = false;
        boolean mapCommitted = false;
        try {
            Files.createDirectories(directory);
            textTemp = Files.createTempFile(directory, ".text-", ".tmp");
            mapTemp = Files.createTempFile(directory, ".source-map-", ".tmp");
            Files.writeString(textTemp, extracted.text(), StandardCharsets.UTF_8);
            objectMapper.writeValue(mapTemp.toFile(),
                    new CacheSidecar(sourceHash, extracted.extractorId(), extracted.extractorVersion(),
                            extracted.mediaType(), sha256(extracted.text()),
                            extracted.sourceSpans(), extracted.metadata()));
            moveAtomically(textTemp, textPath);
            textTemp = null;
            textCommitted = true;
            moveAtomically(mapTemp, mapPath);
            mapTemp = null;
            mapCommitted = true;
        } catch (IOException exception) {
            if (textCommitted || mapCommitted) {
                try {
                    Files.deleteIfExists(textPath);
                    Files.deleteIfExists(mapPath);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw new IllegalStateException("Extraction cache files could not be saved", exception);
        } finally {
            deleteTemporary(textTemp);
            deleteTemporary(mapTemp);
        }
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Managed extraction storage does not support atomic replacement", exception);
        }
    }

    private void deleteExistingPair(FileTextExtraction row, long tenantId, long fileId) {
        Path directory = scopedDirectory(tenantId, fileId);
        Path textPath = checked(Path.of(row.getManagedTextPath()), directory);
        Path mapPath = checked(Path.of(row.getSourceMapPath()), directory);
        try {
            Files.deleteIfExists(textPath);
            Files.deleteIfExists(mapPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Managed extraction files could not be removed", exception);
        }
    }

    private void validateManagedPaths(FileTextExtraction row, long tenantId, long fileId) {
        Path directory = scopedDirectory(tenantId, fileId);
        checked(Path.of(row.getManagedTextPath()), directory);
        checked(Path.of(row.getSourceMapPath()), directory);
    }

    private void deleteNewPair(Path textPath, Path mapPath, long tenantId, long fileId,
                               RuntimeException failure) {
        try {
            Files.deleteIfExists(textPath);
            Files.deleteIfExists(mapPath);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            log.error("Unable to clean new extraction cache files for tenant {} file {}",
                    tenantId, fileId, cleanupFailure);
        }
    }

    private void deleteTemporary(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Unable to remove an extraction cache temporary file");
        }
    }

    private Path scopedDirectory(long tenantId, long fileId) {
        return checked(root.resolve(Long.toString(tenantId)).resolve(Long.toString(fileId)), root);
    }

    private Path checked(Path candidate, Path requiredParent) {
        Path normalized = candidate.toAbsolutePath().normalize();
        Path parent = requiredParent.toAbsolutePath().normalize();
        if (!normalized.startsWith(parent)) {
            throw new IllegalStateException("Managed extraction path escapes its tenant/file directory");
        }
        return normalized;
    }

    private FileTextExtraction metadata(long tenantId, long fileId, String sourceHash,
                                        ExtractedText extracted, Path textPath, Path mapPath) {
        FileTextExtraction row = new FileTextExtraction();
        row.setTenantId(tenantId);
        row.setFileId(fileId);
        row.setSourceHash(sourceHash);
        row.setExtractorId(extracted.extractorId());
        row.setExtractorVersion(extracted.extractorVersion());
        row.setMediaType(extracted.mediaType());
        row.setManagedTextPath(textPath.toString());
        row.setSourceMapPath(mapPath.toString());
        row.setCharacterCount((long) extracted.text().codePointCount(0, extracted.text().length()));
        return row;
    }

    private void requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record CacheSidecar(String sourceHash, String extractorId, String extractorVersion,
                                String mediaType, String textHash,
                                List<SourceSpan> sourceSpans, Map<String, Object> metadata) {
        private CacheSidecar {
            sourceSpans = sourceSpans == null ? List.of() : List.copyOf(sourceSpans);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
