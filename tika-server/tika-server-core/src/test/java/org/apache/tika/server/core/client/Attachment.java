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
package org.apache.tika.server.core.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;


/**
 * One part of a multipart request body: its headers and content.
 */
public final class Attachment {

    private final List<String[]> headers = new ArrayList<>();
    private final byte[] content;

    /**
     * A part with a <code>Content-ID</code> and a <code>Content-Type</code>.
     */
    public Attachment(String id, String mediaType, InputStream content) {
        this(content);
        headers.add(new String[]{"Content-ID", id});
        headers.add(new String[]{"Content-Type", mediaType});
    }

    /**
     * A part with a <code>Content-ID</code> and a <code>Content-Disposition</code>.
     */
    public Attachment(String id, InputStream content, ContentDisposition disposition) {
        this(content);
        headers.add(new String[]{"Content-ID", id});
        headers.add(new String[]{"Content-Disposition", disposition.toString()});
    }

    private Attachment(InputStream content) {
        try (InputStream is = content) {
            this.content = IOUtils.toByteArray(is);
        } catch (IOException e) {
            throw new ProcessingException(e);
        }
    }

    /**
     * Adds a part header.
     */
    public Attachment header(String name, String value) {
        headers.add(new String[]{name, value});
        return this;
    }

    List<String[]> getHeaders() {
        return headers;
    }

    byte[] getContent() {
        return content;
    }
}
