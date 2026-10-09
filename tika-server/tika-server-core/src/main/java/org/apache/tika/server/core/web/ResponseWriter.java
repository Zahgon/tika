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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;

/**
 * Sends the responses of tika-server.
 * <p>
 * An entity is written by the first registered {@link HttpMessageConverter} that writes its
 * class as the response's media type (the type chosen for the request, unless the response
 * names one); strings, byte arrays and {@link StreamingOutput}s are written as they are, a
 * string in the media type's charset (UTF-8 by default). The body is produced in full
 * before anything is sent, so a failure while writing still turns into an error response.
 * <p>
 * Bodies of 1024 bytes or more are gzip-compressed when the request's
 * <code>Accept-Encoding</code> allows it, with <code>Content-Encoding</code> and
 * <code>Vary: Accept-Encoding</code> added.
 */
public class ResponseWriter {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseWriter.class);
    private static final int GZIP_THRESHOLD = 1024;
    private static final Pattern ZERO_Q = Pattern.compile(";\\s*q=0(?:\\.0+)?$");
    private static final Pattern ENCODINGS = Pattern.compile("[,\\s]*,\\s*");
    private static final Pattern CHARSET = Pattern.compile("(?i);\\s*charset=([^;]*)");

    private final List<HttpMessageConverter<?>> writers;
    private final List<ResponseFilter> filters;

    public ResponseWriter(List<HttpMessageConverter<?>> writers, List<ResponseFilter> filters) {
        this.writers = new ArrayList<>(writers);
        this.filters = new ArrayList<>(filters);
    }

    /**
     * Writes the result of an endpoint.
     *
     * @param request   the request
     * @param response  the servlet response
     * @param result    what the endpoint returned: a {@link ResponseEntity}, an entity, or null
     * @param selection the selected endpoint
     * @throws IOException when an entity cannot be written
     */
    public void writeResult(HttpServletRequest request, HttpServletResponse response, Object result, ResourceSelector.Selection selection)
            throws IOException {
        ResponseEntity<?> entity;
        if (result instanceof ResponseEntity) {
            entity = (ResponseEntity<?>) result;
        } else if (result == null) {
            entity = ResponseEntity.noContent().build();
        } else {
            entity = ResponseEntity.ok(result);
        }
        write(request, response, entity, selection);
    }

    /**
     * Writes a response.
     *
     * @param request   the request
     * @param response  the servlet response
     * @param entity    status, headers and entity
     * @param selection the selected endpoint, or null when the request reached none
     * @throws IOException when the entity cannot be written
     */
    public void write(HttpServletRequest request, HttpServletResponse response, ResponseEntity<?> entity, ResourceSelector.Selection selection)
            throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(entity.getHeaders());
        String encoding = gzipEncoding(request);
        byte[] body = new byte[0];
        Object value = entity.getBody();
        if (value != null) {
            String contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
            if (contentType == null) {
                WebMediaType type = selection == null ? WebMediaType.APPLICATION_OCTET_STREAM_TYPE : selection.responseType();
                contentType = type.toString(MediaTypes.Q_PARAM, MediaTypes.QS_PARAM);
                headers.set(HttpHeaders.CONTENT_TYPE, contentType);
            }
            try {
                body = render(value, contentType, encoding == null ? 0 : GZIP_THRESHOLD);
            } catch (PartialOutputException e) {
                if (!e.isCommitted()) {
                    throw e;
                }
                // the entity had flushed part of itself: the response was already on its way
                LOG.warn("writing the response of {} {} failed after it was sent", request.getMethod(), request.getRequestURI(), e.getCause());
                body = e.getOutput();
            }
        }
        if (entity instanceof PartialResponse partial) {
            body = concat(partial.getOutput(), body);
        }
        int status = entity.getStatusCode().value();
        RequestContext context = RequestContext.of(request);
        for (ResponseFilter filter : filters) {
            filter.filter(request, context, status, headers);
        }
        send(response, status, headers, body, encoding);
    }

    /**
     * @param holdBelow while compression may still apply, output shorter than this stays buffered even
     *                  when the entity flushes it
     */
    private byte[] render(Object value, String contentType, int holdBelow) throws IOException {
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            // a type Spring cannot parse is still sent as it is; the writers see it as bytes
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        BufferedOutputMessage out = new BufferedOutputMessage();
        out.getHeaders().setContentType(mediaType);
        if (value instanceof StreamingOutput) {
            FlushTrackingStream body = new FlushTrackingStream(holdBelow);
            try {
                ((StreamingOutput) value).write(body);
            } catch (IOException | RuntimeException e) {
                throw new PartialOutputException(body.toByteArray(), body.isFlushed(), e);
            }
            return body.toByteArray();
        }
        for (HttpMessageConverter<?> writer : writers) {
            if (writer.canWrite(value.getClass(), mediaType)) {
                write(writer, value, mediaType, out);
                return out.toByteArray();
            }
        }
        if (value instanceof String) {
            return ((String) value).getBytes(charset(mediaType));
        }
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        throw new HttpStatusException("No message body writer has been found for class " + value.getClass().getName() + ", ContentType: " + contentType);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] all = new byte[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    @SuppressWarnings("unchecked")
    private static <T> void write(HttpMessageConverter<T> writer, Object value, MediaType mediaType, HttpOutputMessage out) throws IOException {
        writer.write((T) value, mediaType, out);
    }

    private static Charset charset(MediaType mediaType) {
        try {
            Charset cs = mediaType.getCharset();
            return cs == null ? StandardCharsets.UTF_8 : cs;
        } catch (IllegalArgumentException e) {
            // a charset the JVM does not know: strings are written in the default
            return StandardCharsets.UTF_8;
        }
    }

    private void send(HttpServletResponse response, int status, HttpHeaders headers, byte[] body, String encoding) throws IOException {
        if (encoding != null && body.length >= GZIP_THRESHOLD) {
            ByteArrayOutputStream zipped = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(zipped)) {
                gz.write(body);
            }
            body = zipped.toByteArray();
            headers.add(HttpHeaders.CONTENT_ENCODING, encoding);
            headers.add(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
        }
        response.setStatus(status);
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(e.getKey())) {
                response.setContentType(lowerCaseCharset(e.getValue().get(0)));
            } else {
                for (String v : e.getValue()) {
                    response.addHeader(e.getKey(), v);
                }
            }
        }
        if (body.length > 0) {
            response.setContentLength(body.length);
            OutputStream os = response.getOutputStream();
            os.write(body);
            os.flush();
        } else {
            response.setContentLength(0);
        }
    }

    /**
     * @return the content type with its charset name in lower case, as tika-server has always sent it
     */
    private static String lowerCaseCharset(String contentType) {
        Matcher m = CHARSET.matcher(contentType);
        if (!m.find()) {
            return contentType;
        }
        return contentType.substring(0, m.start(1)) + m.group(1).toLowerCase(Locale.ROOT) + contentType.substring(m.end(1));
    }

    /**
     * @return the encoding to compress with (from 1024 bytes on), or null when the response is sent as it is
     * @throws HttpStatusException when the request accepts no encoding the server has
     */
    private static String gzipEncoding(HttpServletRequest request) {
        List<String> lines = new ArrayList<>();
        for (var e = request.getHeaders(HttpHeaders.ACCEPT_ENCODING); e != null && e.hasMoreElements(); ) {
            lines.add(e.nextElement());
        }
        if (lines.isEmpty()) {
            return null;
        }
        List<String> zeros = new ArrayList<>();
        List<String> nonZeros = new ArrayList<>();
        for (String line : lines) {
            for (String enc : ENCODINGS.split(line.trim())) {
                Matcher m = ZERO_Q.matcher(enc);
                if (m.find()) {
                    zeros.add(enc.substring(0, m.start()));
                } else if (enc.indexOf(';') >= 0) {
                    nonZeros.add(enc.substring(0, enc.indexOf(';')));
                } else {
                    nonZeros.add(enc);
                }
            }
        }
        boolean identity = !zeros.contains("identity") && (!zeros.contains("*") || nonZeros.contains("identity"));
        boolean gzip = nonZeros.contains("gzip") || nonZeros.contains("*") && !zeros.contains("gzip");
        boolean xGzip = nonZeros.contains("x-gzip") || nonZeros.contains("*") && !zeros.contains("x-gzip");
        if (identity && !gzip && !xGzip) {
            return null;
        } else if (gzip) {
            return "gzip";
        } else if (xGzip) {
            return "x-gzip";
        }
        LOG.warn("no supported content encoding in {}", lines);
        throw new HttpStatusException("No supported content encoding");
    }

    /**
     * The response body of a {@link StreamingOutput}, noting whether the entity flushed it.
     */
    private static final class FlushTrackingStream extends ByteArrayOutputStream {
        private final int holdBelow;
        private boolean flushed;

        FlushTrackingStream(int holdBelow) {
            this.holdBelow = holdBelow;
        }

        @Override
        public void flush() {
            if (size() > 0 && size() >= holdBelow) {
                flushed = true;
            }
        }

        boolean isFlushed() {
            return flushed;
        }
    }

    private static final class BufferedOutputMessage implements HttpOutputMessage {
        private final HttpHeaders headers = new HttpHeaders();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        @Override
        public OutputStream getBody() {
            return body;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        byte[] toByteArray() {
            return body.toByteArray();
        }
    }
}
