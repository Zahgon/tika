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
package org.apache.tika.server.core.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriUtils;

/**
 * What a tika-server endpoint sees of its request: the headers, the path and the query.
 * <p>
 * Header names are case-insensitive and iterate in case-insensitive alphabetical order;
 * each name keeps the spelling it first arrived with. Repeated headers keep every value
 * (for <code>Accept</code> the first value also accumulates the later ones, comma-separated).
 * <code>Content-Type</code> is always present, with a null value when the request has none;
 * <code>Content-Encoding</code> is left out when {@link RequestBody} decompressed the body.
 */
public final class RequestContext {

    /**
     * Request attribute holding the context of the current request.
     */
    public static final String ATTRIBUTE = RequestContext.class.getName();

    private final MultiValueMap<String, String> headers;
    private final String path;
    private final String rawQuery;

    private RequestContext(MultiValueMap<String, String> headers, String path, String rawQuery) {
        this.headers = headers;
        this.path = path;
        this.rawQuery = rawQuery;
    }

    /**
     * @param request the servlet request
     * @return its context, created once per request
     */
    public static RequestContext of(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof RequestContext) {
            return (RequestContext) existing;
        }
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        boolean decompressed = request.getAttribute(RequestBody.DECOMPRESSED) != null;
        for (Enumeration<String> names = request.getHeaderNames(); names.hasMoreElements(); ) {
            String name = names.nextElement();
            if (decompressed && HttpHeaders.CONTENT_ENCODING.equalsIgnoreCase(name)) {
                continue;
            }
            List<String> values = map.computeIfAbsent(name, k -> new ArrayList<>());
            for (Enumeration<String> e = request.getHeaders(name); e.hasMoreElements(); ) {
                String value = e.nextElement();
                if ("Accept".equalsIgnoreCase(name) && !values.isEmpty()) {
                    values.set(0, values.get(0) + ", " + value);
                }
                values.add(value);
            }
        }
        if (!map.containsKey("Content-Type")) {
            map.put("Content-Type", Collections.singletonList(request.getContentType()));
        }
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String rawPath = uri.startsWith(context) ? uri.substring(context.length()) : uri;
        RequestContext ctx = new RequestContext(CollectionUtils.unmodifiableMultiValueMap(CollectionUtils.toMultiValueMap(map)), rawPath, request.getQueryString());
        request.setAttribute(ATTRIBUTE, ctx);
        return ctx;
    }

    /**
     * @return the request headers
     */
    public MultiValueMap<String, String> getHeaders() {
        return headers;
    }

    /**
     * @param name a header name
     * @return its values joined with ",", or null when absent
     */
    public String getHeaderString(String name) {
        List<String> values = headers.get(name);
        if (values == null) {
            return null;
        }
        if (values.isEmpty()) {
            return "";
        }
        return String.join(",", values);
    }

    /**
     * @return the request path as sent (percent-encoded), starting with "/"
     */
    public String getRawPath() {
        return path.isEmpty() ? "/" : path;
    }

    /**
     * @return the decoded request path without its leading "/"
     */
    public String getPath() {
        String p = UriUtils.decode(getRawPath(), StandardCharsets.UTF_8);
        return p.startsWith("/") ? p.substring(1) : p;
    }

    /**
     * @return the decoded query parameters, in order
     */
    public MultiValueMap<String, String> getQueryParameters() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.add(UriUtils.decode(name.replace('+', ' '), StandardCharsets.UTF_8), UriUtils.decode(value.replace('+', ' '), StandardCharsets.UTF_8));
        }
        return params;
    }
}
