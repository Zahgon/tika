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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;

import org.apache.commons.io.IOUtils;
import org.springframework.util.MultiValueMap;


/**
 * A small fluent HTTP client for the server tests: set the request's content type, accepted
 * types and headers, then send it with {@link #get()}, {@link #put(Object)} or
 * {@link #post(Object)}. Bodies may be an {@link InputStream}, a {@link String}, a byte array
 * or a {@link MultipartBody}. Every call returns the whole {@link Response}; connection
 * failures are {@link ProcessingException}s.
 */
public final class WebClient {

    private static final HttpClient CLIENT = builder().build();

    private HttpClient client = CLIENT;
    private final String address;
    private final List<String[]> headers = new ArrayList<>();
    private final StringBuilder query = new StringBuilder();
    private Duration timeout = Duration.ofMinutes(5);

    private WebClient(String address) {
        this.address = address;
    }

    /**
     * @param address the absolute URL to call
     * @return a client for it
     */
    public static WebClient create(String address) {
        return new WebClient(address);
    }

    private static HttpClient.Builder builder() {
        return HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30));
    }

    /**
     * Sends the requests of this client over TLS with the given context.
     */
    public WebClient sslContext(SSLContext context) {
        this.client = builder().sslContext(context).build();
        return this;
    }

    /**
     * Adds every header of the map; several values of one name are joined with ",".
     */
    public WebClient headers(MultiValueMap<String, String> map) {
        map.forEach((name, values) -> headers.add(new String[]{name, String.join(",", values)}));
        return this;
    }

    public WebClient type(String contentType) {
        return replaceHeader("Content-Type", contentType);
    }

    public WebClient accept(String... types) {
        for (String t : types) {
            headers.add(new String[]{"Accept", t});
        }
        return this;
    }

    public WebClient acceptEncoding(String... encodings) {
        return header("Accept-Encoding", (Object[]) encodings);
    }

    public WebClient encoding(String encoding) {
        return replaceHeader("Content-Encoding", encoding);
    }

    /**
     * Adds a header; several values are joined with ", ".
     */
    public WebClient header(String name, Object... values) {
        List<String> strings = new ArrayList<>();
        for (Object v : values) {
            strings.add(String.valueOf(v));
        }
        headers.add(new String[]{name, String.join(", ", strings)});
        return this;
    }

    public WebClient replaceHeader(String name, String value) {
        headers.removeIf(h -> h[0].equalsIgnoreCase(name));
        if (value != null) {
            headers.add(new String[]{name, value});
        }
        return this;
    }

    public WebClient query(String name, Object value) {
        query.append(query.length() == 0 ? '?' : '&');
        query.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
        return this;
    }

    public WebClient timeout(Duration requestTimeout) {
        this.timeout = requestTimeout;
        return this;
    }

    public Response get() {
        return invoke("GET", null);
    }

    /**
     * @return the body of a GET response as UTF-8 text
     */
    public String get(Class<String> type) {
        return get().readEntity();
    }

    public void close() {
        // every request uses its own exchange; nothing to release
    }

    public Response put(Object body) {
        return invoke("PUT", body);
    }

    public Response post(Object body) {
        return invoke("POST", body);
    }

    public Response options() {
        return invoke("OPTIONS", null);
    }

    /**
     * Sends the request.
     *
     * @param method the HTTP method
     * @param body   the body, or null
     * @return the response, its entity fully read
     */
    public Response invoke(String method, Object body) {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(address + query))
                .timeout(timeout);
        byte[] bytes = toBytes(body);
        if (body instanceof MultipartBody multipart) {
            replaceHeader("Content-Type", multipart.getContentType());
        }
        try {
            for (String[] h : headers) {
                builder.header(h[0], h[1]);
            }
        } catch (IllegalArgumentException e) {
            // a header the HTTP client refuses to send, such as one with control characters
            throw new ProcessingException(e);
        }
        builder.method(method, bytes == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes));
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new ProcessingException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingException(e);
        }
    }

    private static byte[] toBytes(Object body) {
        try {
            if (body == null) {
                return null;
            } else if (body instanceof byte[]) {
                return (byte[]) body;
            } else if (body instanceof String) {
                return ((String) body).getBytes(StandardCharsets.UTF_8);
            } else if (body instanceof InputStream) {
                try (InputStream is = (InputStream) body) {
                    return IOUtils.toByteArray(is);
                }
            } else if (body instanceof MultipartBody) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ((MultipartBody) body).writeTo(out);
                return out.toByteArray();
            } else if (body instanceof Attachment) {
                return toBytes(new MultipartBody((Attachment) body));
            }
        } catch (IOException e) {
            throw new ProcessingException(e);
        }
        throw new IllegalArgumentException("unsupported body " + body.getClass());
    }

    /**
     * @return an input stream over the given bytes
     */
    static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
