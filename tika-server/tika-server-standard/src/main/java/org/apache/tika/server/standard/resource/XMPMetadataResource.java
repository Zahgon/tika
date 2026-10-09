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
package org.apache.tika.server.standard.resource;

import java.io.InputStream;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.server.core.resource.MetadataResource;
import org.apache.tika.server.core.resource.TikaResource;
import org.apache.tika.server.core.resource.TikaServerResource;
import org.apache.tika.server.core.web.Attachment;
import org.apache.tika.server.core.web.RequestContext;

public class XMPMetadataResource extends MetadataResource implements TikaServerResource {

    @PutMapping(path = "{field}", produces = {"application/rdf+xml"})
    @Override
    public ResponseEntity<?> getMetadataField(InputStream is, RequestContext request, @PathVariable("field") String field) throws Exception {
        return super.getMetadataField(is, request, field);
    }

    @PostMapping(path = "form", consumes = "multipart/form-data", produces = {"application/rdf+xml"})
    public ResponseEntity<?> getMetadataFromMultipart(Attachment att, RequestContext request) throws Exception {
        return ResponseEntity.ok(parseMetadata(att.getInputStream(), new Metadata(), att.getHeaders(), request));
    }

    @PutMapping(produces = {"application/rdf+xml"})
    public ResponseEntity<?> getMetadata(InputStream is, RequestContext request) throws Exception {
        Metadata metadata = new Metadata();
        return ResponseEntity.ok(parseMetadata(TikaResource.getInputStream(is, metadata, request), metadata, request.getHeaders(), request));
    }
}
