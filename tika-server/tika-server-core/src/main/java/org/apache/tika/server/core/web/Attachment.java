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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.springframework.http.HttpStatus;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;

/**
 * The root part of a multipart request body: the part the <code>/form</code> endpoints
 * parse. That is the first part after the first boundary; its headers are case-insensitive,
 * and a <code>base64</code> or <code>quoted-printable</code> transfer encoding is decoded
 * (and its header dropped).
 */
public final class Attachment {

    private static final Pattern CONTENT_TYPE_BOUNDARY = Pattern.compile("boundary=\"?([^\";]*)");
    private static final Pattern STREAM_BOUNDARY = Pattern.compile("^--(\\S*)$", Pattern.MULTILINE);
    private static final String ROOT_ID = "root.message";

    private final MultiValueMap<String, String> headers;
    private final byte[] body;

    private Attachment(MultiValueMap<String, String> headers, byte[] body) {
        this.headers = headers;
        this.body = body;
    }

    /**
     * Reads the root part of a multipart body.
     *
     * @param contentType the request's content type
     * @param in          the request body
     * @return the root part
     * @throws IOException when the body has no boundary, or the part an unknown transfer encoding
     * @throws HttpStatusException (415) when the body is not multipart
     */
    public static Attachment readRoot(String contentType, InputStream in) throws IOException {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("multipart/")) {
            throw new HttpStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        byte[] data = IOUtils.toByteArray(in);
        String boundaryString = null;
        Matcher m = CONTENT_TYPE_BOUNDARY.matcher(contentType);
        if (m.find()) {
            boundaryString = "--" + m.group(1);
        } else {
            String start = new String(data, 0, Math.min(2048, data.length), StandardCharsets.UTF_8);
            Matcher sm = STREAM_BOUNDARY.matcher(start);
            if (sm.find()) {
                boundaryString = "--" + sm.group(1);
            }
        }
        if (boundaryString == null) {
            throw new IOException("Couldn't determine the boundary from the message!");
        }
        byte[] boundary = boundaryString.getBytes(StandardCharsets.UTF_8);
        int pos = indexOf(data, boundary, 0);
        if (pos < 0) {
            throw new IOException("Couldn't find MIME boundary: " + boundaryString);
        }
        pos += boundary.length;
        if (pos >= data.length) {
            throw new IOException("Unexpected End while searching for first Mime Boundary");
        }
        // the byte after the boundary is consumed; after a CR, so is the next one
        if (data[pos++] == '\r') {
            pos++;
        }
        Map<String, List<String>> heads = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        pos = readHeaders(data, pos, heads);
        int end = partEnd(data, pos, boundary);
        byte[] part = new byte[end - pos];
        System.arraycopy(data, pos, part, 0, part.length);
        String encoding = transferEncoding(heads);
        if ("base64".equals(encoding)) {
            part = Base64.getMimeDecoder().decode(part);
            heads.remove("Content-Transfer-Encoding");
        } else if ("quoted-printable".equals(encoding)) {
            part = decodeQuotedPrintable(part);
            heads.remove("Content-Transfer-Encoding");
        }
        checkFollowingParts(data, end, boundary);
        MultiValueMap<String, String> headers = CollectionUtils.toMultiValueMap(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
        for (Map.Entry<String, List<String>> e : heads.entrySet()) {
            if (!"Content-ID".equalsIgnoreCase(e.getKey())) {
                headers.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        List<String> ids = heads.get("Content-ID");
        String id = ids == null || ids.isEmpty() ? ROOT_ID : ids.get(0);
        headers.put("Content-ID", new ArrayList<>(List.of(id)));
        return new Attachment(headers, part);
    }

    /**
     * Reads part headers up to the empty line that ends them.
     *
     * @return the position after that line
     */
    private static int readHeaders(byte[] data, int pos, Map<String, List<String>> heads) {
        StringBuilder pending = new StringBuilder();
        StringBuilder line = new StringBuilder();
        while (true) {
            line.setLength(0);
            while (pos < data.length) {
                int c = data[pos++] & 0xff;
                if (c == '\n') {
                    break;
                } else if (c != '\r') {
                    line.append((char) c);
                }
            }
            if (line.length() == 0) {
                break;
            }
            char first = line.charAt(0);
            if (first == ' ' || first == '\t') {
                if (pending.length() != 0) {
                    pending.append("\r\n").append(line);
                }
            } else {
                addHeaderLine(heads, pending);
                pending.setLength(0);
                pending.append(line);
            }
        }
        addHeaderLine(heads, pending);
        return pos;
    }

    /**
     * @return the part's transfer encoding in lower case, or null
     * @throws IOException when it is not one of base64, quoted-printable, binary, 7bit and 8bit
     */
    private static String transferEncoding(Map<String, List<String>> heads) throws IOException {
        List<String> encodings = heads.get("Content-Transfer-Encoding");
        if (encodings == null || encodings.isEmpty()) {
            return null;
        }
        String encoding = encodings.get(0).toLowerCase(Locale.ROOT);
        switch (encoding) {
            case "base64":
            case "quoted-printable":
            case "binary":
            case "7bit":
            case "8bit":
                return encoding;
            default:
                throw new IOException("Unknown encoding " + encoding);
        }
    }

    /**
     * The parts after the root are read too: their transfer encodings must be valid.
     */
    private static void checkFollowingParts(byte[] data, int from, byte[] boundary) throws IOException {
        int next = indexOf(data, boundary, from);
        while (next >= 0) {
            int pos = next + boundary.length;
            if (pos + 1 >= data.length || data[pos] == '-' && data[pos + 1] == '-') {
                return;
            }
            if (data[pos] == '\r') {
                pos++;
            }
            if (pos < data.length && data[pos] == '\n') {
                pos++;
            }
            Map<String, List<String>> heads = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            pos = readHeaders(data, pos, heads);
            transferEncoding(heads);
            next = indexOf(data, boundary, pos);
        }
    }

    private static void addHeaderLine(Map<String, List<String>> heads, CharSequence line) {
        if (line.length() == 0) {
            return;
        }
        String s = line.toString();
        int separator = s.indexOf(':');
        String name;
        String value = "";
        if (separator == -1) {
            name = s.trim();
        } else {
            name = s.substring(0, separator);
            separator++;
            while (separator < s.length() && " \t\r\n".indexOf(s.charAt(separator)) >= 0) {
                separator++;
            }
            value = s.substring(separator);
        }
        heads.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
    }

    /**
     * @return the index where the part starting at <code>from</code> ends: before the next boundary,
     * and before the CRLF in front of it when there is one
     */
    private static int partEnd(byte[] data, int from, byte[] boundary) {
        int b = indexOf(data, boundary, from);
        if (b < 0) {
            return data.length;
        }
        if (b - 2 >= from && data[b - 2] == '\r' && data[b - 1] == '\n') {
            return b - 2;
        }
        return b;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] decodeQuotedPrintable(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            byte b = in[i];
            if (b == '=') {
                if (i + 2 < in.length && in[i + 1] == '\r' && in[i + 2] == '\n') {
                    i += 2;
                } else if (i + 1 < in.length && in[i + 1] == '\n') {
                    i += 1;
                } else if (i + 2 < in.length) {
                    out.write(Character.digit(in[i + 1], 16) << 4 | Character.digit(in[i + 2], 16));
                    i += 2;
                }
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    /**
     * @return the part's headers, case-insensitive
     */
    public MultiValueMap<String, String> getHeaders() {
        return headers;
    }

    /**
     * @param name a header name
     * @return its first value, or null
     */
    public String getHeader(String name) {
        return headers.getFirst(name);
    }

    /**
     * @return the part's (decoded) content
     */
    public InputStream getInputStream() {
        return new ByteArrayInputStream(body);
    }
}
