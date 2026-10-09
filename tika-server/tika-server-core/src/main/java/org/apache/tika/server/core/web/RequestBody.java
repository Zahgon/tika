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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * The body of a request as the endpoints read it, prepared before the request is routed.
 * <ul>
 *     <li>A request fails with 500 when it declares a charset the JVM does not know: the
 *     <code>charset=</code> of its content type (else the servlet container's reading of it),
 *     checked for every request that names one and for every PUT and POST.</li>
 *     <li>A body sent with <code>Content-Encoding: gzip</code> (or <code>x-gzip</code>) on any
 *     method but GET is decompressed, and the endpoints do not see that header; a body that is
 *     not gzip data fails the request with 500.</li>
 * </ul>
 */
public final class RequestBody {

    /**
     * Request attribute set when the body is decompressed.
     */
    public static final String DECOMPRESSED = RequestBody.class.getName() + ".decompressed";
    private static final String STREAM = RequestBody.class.getName() + ".stream";

    private RequestBody() {
    }

    /**
     * Checks the request's charset and opens its body.
     *
     * @param request the request
     * @throws HttpStatusException (500) for an unknown charset or a body that is not the gzip data it claims to be
     */
    public static void prepare(HttpServletRequest request) {
        if (request.getAttribute(STREAM) != null) {
            return;
        }
        String enc = findCharset(request.getHeader(HttpHeaders.CONTENT_TYPE));
        if (enc == null) {
            enc = request.getCharacterEncoding();
        }
        if (enc != null && enc.endsWith("\"")) {
            enc = enc.substring(0, enc.length() - 1);
        }
        if ((enc != null || "POST".equals(request.getMethod()) || "PUT".equals(request.getMethod())) && !isKnown(enc)) {
            throw new HttpStatusException("Invalid character encoding " + enc);
        }
        try {
            InputStream body = request.getInputStream();
            if (!"GET".equals(request.getMethod()) && isGzip(request)) {
                body = new GZIPInputStream(body);
                request.setAttribute(DECOMPRESSED, Boolean.TRUE);
            }
            request.setAttribute(STREAM, body);
        } catch (IOException e) {
            throw new HttpStatusException(e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * @param request a prepared request
     * @return its body, decompressed when it was sent compressed
     */
    public static InputStream stream(HttpServletRequest request) {
        prepare(request);
        return (InputStream) request.getAttribute(STREAM);
    }

    private static boolean isGzip(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(HttpHeaders.CONTENT_ENCODING));
        return values.contains("gzip") || values.contains("x-gzip");
    }

    private static String findCharset(String contentType) {
        if (contentType == null) {
            return null;
        }
        int idx = contentType.indexOf("charset=");
        if (idx == -1) {
            return null;
        }
        String charset = contentType.substring(idx + 8);
        if (charset.indexOf(';') != -1) {
            charset = charset.substring(0, charset.indexOf(';')).trim();
        }
        if (charset.isEmpty()) {
            return null;
        }
        if (charset.charAt(0) == '"') {
            charset = charset.substring(1, charset.length() - 1);
        }
        return charset;
    }

    private static boolean isKnown(String enc) {
        if (enc == null) {
            return true;
        }
        int idx = enc.indexOf(';');
        if (idx != -1) {
            enc = enc.substring(0, idx);
        }
        enc = enc.replace('"', ' ').replace('\'', ' ').trim();
        if (enc.isEmpty()) {
            return true;
        }
        try {
            Charset.forName(enc);
            return true;
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            // the JVM cannot decode it: the request fails
            return false;
        }
    }
}
