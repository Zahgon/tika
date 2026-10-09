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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.HandlerAdapter;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriUtils;

/**
 * Invokes tika-server endpoints and writes what they return.
 * <p>
 * An endpoint method may declare, in any order: an {@link InputStream} (the request body, see
 * {@link RequestBody}), a
 * {@link String} (the body decoded with the request's charset, UTF-8 by default), an
 * {@link Attachment} (the root part of a multipart body), the {@link RequestContext},
 * the {@link HttpServletRequest}, and {@link PathVariable} strings: the last value the variable
 * matched, up to any ';' and without a leading '/', decoded; null when it matched nothing.
 */
public class ResourceHandlerAdapter implements HandlerAdapter {

    private final ResponseWriter writer;

    public ResourceHandlerAdapter(ResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public boolean supports(Object handler) {
        return handler instanceof ResourceSelector.Selection || handler instanceof ResourceHandlerMapping.FixedResponse;
    }

    @Override
    public ModelAndView handle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (handler instanceof ResourceHandlerMapping.FixedResponse fixed) {
            writer.write(request, response, fixed.response(), null);
            return null;
        }
        ResourceSelector.Selection selection = (ResourceSelector.Selection) handler;
        Method method = selection.method().getMethod();
        Object[] args = resolveArguments(method, selection, request);
        Object result;
        try {
            result = method.invoke(selection.method().getResourceClass().getResource(), args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
        writer.writeResult(request, response, result, selection);
        return null;
    }

    private Object[] resolveArguments(Method method, ResourceSelector.Selection selection, HttpServletRequest request) throws IOException {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];
        RequestContext context = RequestContext.of(request);
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            PathVariable pv = p.getAnnotation(PathVariable.class);
            Class<?> type = p.getType();
            if (pv != null) {
                String name = pv.value().isEmpty() ? pv.name() : pv.value();
                args[i] = pathValue(selection.pathValues().get(name));
            } else if (type == InputStream.class) {
                args[i] = RequestBody.stream(request);
            } else if (type == String.class) {
                args[i] = IOUtils.toString(RequestBody.stream(request), requestCharset(context));
            } else if (type == Attachment.class) {
                args[i] = Attachment.readRoot(context.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE), RequestBody.stream(request));
            } else if (type == RequestContext.class) {
                args[i] = context;
            } else if (type == HttpServletRequest.class) {
                args[i] = request;
            } else {
                throw new IllegalStateException("unsupported endpoint parameter " + p + " of " + method);
            }
        }
        return args;
    }

    /**
     * @return the last value a path variable matched, up to any ';' and without a leading '/', decoded
     */
    private static String pathValue(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String raw = values.get(values.size() - 1);
        if (raw == null) {
            return null;
        }
        int semicolon = raw.indexOf(';');
        String value = semicolon != -1 ? raw.substring(0, semicolon) : raw;
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        return UriUtils.decode(value, StandardCharsets.UTF_8);
    }

    private static Charset requestCharset(RequestContext context) {
        String ct = context.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        if (ct != null) {
            try {
                String cs = MediaTypes.toMediaType(ct).getParameters().get("charset");
                if (cs != null) {
                    return Charset.forName(cs);
                }
            } catch (IllegalArgumentException e) {
                // an unknown charset or a broken content type: the default applies
            }
        }
        return StandardCharsets.UTF_8;
    }

    @Override
    @SuppressWarnings("deprecation")
    public long getLastModified(HttpServletRequest request, Object handler) {
        return -1;
    }
}
