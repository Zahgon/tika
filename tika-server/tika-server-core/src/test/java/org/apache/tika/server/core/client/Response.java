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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An HTTP response as the server tests see it: status, headers (case-insensitive) and entity.
 */
public final class Response {

    /**
     * The statuses the tests compare with.
     */
    public enum Status {
        OK(200), NO_CONTENT(204), BAD_REQUEST(400), NOT_FOUND(404), METHOD_NOT_ALLOWED(405), NOT_ACCEPTABLE(406),
        UNSUPPORTED_MEDIA_TYPE(415), UNPROCESSABLE_ENTITY(422), INTERNAL_SERVER_ERROR(500), SERVICE_UNAVAILABLE(503);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        public int getStatusCode() {
            return code;
        }
    }

    private final int status;
    private final Map<String, List<Object>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final byte[] entity;

    Response(int status, Map<String, List<String>> headers, byte[] entity) {
        this.status = status;
        headers.forEach((k, v) -> this.headers.put(k, new ArrayList<>(v)));
        this.entity = entity;
    }

    public int getStatus() {
        return status;
    }

    /**
     * @return a fresh stream over the response body
     */
    public Object getEntity() {
        return WebClient.stream(entity);
    }

    /**
     * @return the response body as UTF-8 text
     */
    public String readEntity() {
        return new String(entity, StandardCharsets.UTF_8);
    }

    public InputStream getEntityStream() {
        return WebClient.stream(entity);
    }

    public Map<String, List<Object>> getHeaders() {
        return headers;
    }

    /**
     * @param name a header name
     * @return its values joined with ",", or null
     */
    public String getHeaderString(String name) {
        List<Object> values = headers.get(name);
        if (values == null) {
            return null;
        }
        List<String> strings = new ArrayList<>();
        for (Object v : values) {
            strings.add(String.valueOf(v));
        }
        return String.join(",", strings);
    }

    public String getMediaType() {
        return getHeaderString("Content-Type");
    }

    public void close() {
        // the entity is held in memory; nothing to release
    }
}
