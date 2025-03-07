/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.plugins.source.file;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import java.util.Objects;

public class FileSourceConfig {
    static final String ATTRIBUTE_PATH = "path";
    static final String ATTRIBUTE_TYPE = "record_type";
    static final String ATTRIBUTE_FORMAT = "format";
    static final int DEFAULT_TIMEOUT = 5_000;
    static final String DEFAULT_TYPE = "string";
    static final String DEFAULT_FORMAT = "plain";
    static final String EVENT_TYPE = "event";


    @JsonProperty(ATTRIBUTE_PATH)
    private String filePathToRead;

    @JsonProperty(ATTRIBUTE_FORMAT)
    private String format = DEFAULT_FORMAT;

    @JsonProperty(ATTRIBUTE_TYPE)
    private String recordType = DEFAULT_TYPE;

    public String getFilePathToRead() {
        return filePathToRead;
    }

    @JsonIgnore
    public FileFormat getFormat() {
        return FileFormat.getByName(format);
    }

    public String getRecordType() {
        return recordType;
    }

    void validate() {
        Objects.requireNonNull(filePathToRead, "File path is required");
        Preconditions.checkArgument(recordType.equals(EVENT_TYPE) || recordType.equals(DEFAULT_TYPE), "Invalid type: must be either [event] or [string]");
        Preconditions.checkArgument(format.equals(DEFAULT_FORMAT) || format.equals("json"), "Invalid file format. Options are [json] and [plain]");
    }
}
