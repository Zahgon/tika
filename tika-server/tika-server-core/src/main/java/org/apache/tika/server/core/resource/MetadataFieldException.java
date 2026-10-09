/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.server.core.resource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.apache.tika.server.core.web.HttpStatusException;

/**
 * A metadata field could not be returned: 400 when the document could not be parsed (there
 * may not have been enough data), 404 when it was parsed but has no such field; the body
 * names the field.
 */
public class MetadataFieldException extends HttpStatusException {

    private static final long serialVersionUID = 1L;

    /**
     * @param field  the requested field
     * @param parsed whether the document was parsed
     */
    public MetadataFieldException(String field, boolean parsed) {
        super(ResponseEntity
                .status(parsed ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST)
                .body("Failed to get metadata field " + field));
    }
}
