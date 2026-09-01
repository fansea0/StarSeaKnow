package com.starsea.ai.chunking.spi;

import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.ParsedStructure;

import java.util.Set;

/** Parses source files into format-neutral structural blocks. */
public interface DocumentStructureParser {

    Set<String> supportedFileTypes();

    ParsedStructure parse(FileResource resource);
}
