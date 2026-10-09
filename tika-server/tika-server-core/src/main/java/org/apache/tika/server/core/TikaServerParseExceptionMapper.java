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
package org.apache.tika.server.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.UnsupportedFormatException;
import org.apache.tika.server.core.web.ExceptionMapper;
import org.apache.tika.server.core.web.HttpStatusException;

/**
 * Maps parse failures to their responses: 422 for encrypted, unsupported or unparseable
 * documents, the wrapped response for HTTP failures, 500 otherwise; with the stack trace as a
 * plain-text body when the server is configured to return it.
 */
public class TikaServerParseExceptionMapper implements ExceptionMapper<TikaServerParseException> {

    private final boolean returnStack;

    public TikaServerParseExceptionMapper(boolean returnStack) {
        this.returnStack = returnStack;
    }

    @Override
    public Class<TikaServerParseException> getExceptionType() {
        return TikaServerParseException.class;
    }

    @Override
    public ResponseEntity<?> toResponse(TikaServerParseException e) {
        if (e.getMessage() != null && e
                .getMessage()
                .equals(HttpStatus.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase())) {
            return buildResponse(e, 415);
        }
        Throwable cause = e.getCause();
        if (cause == null) {
            return buildResponse(e, HttpStatus.INTERNAL_SERVER_ERROR.value());
        } else {
            if (cause instanceof EncryptedDocumentException) {
                return buildResponse(cause, 422);
            } else if (cause instanceof TikaException) {
                //unsupported media type
                Throwable causeOfCause = cause.getCause();
                if (causeOfCause instanceof HttpStatusException) {
                    return ((HttpStatusException) causeOfCause).getResponse();
                }
                return buildResponse(cause, 422);
            } else if (cause instanceof IllegalStateException) {
                return buildResponse(cause, 422);
            } else if (cause instanceof UnsupportedFormatException) {
                return buildResponse(cause, 422);
            } else if (cause instanceof HttpStatusException) {
                return ((HttpStatusException) e.getCause()).getResponse();
            } else {
                return buildResponse(e, 500);
            }
        }
    }

    private ResponseEntity<?> buildResponse(Throwable cause, int i) {
        if (returnStack && cause != null) {
            Writer result = new StringWriter();
            PrintWriter writer = new PrintWriter(result);
            cause.printStackTrace(writer);
            writer.flush();
            try {
                result.flush();
            } catch (IOException e) {
                //something went seriously wrong
                return ResponseEntity
                        .status(500)
                        .build();
            }
            return ResponseEntity
                    .status(i)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(result.toString());
        } else {
            return ResponseEntity
                    .status(i)
                    .build();
        }
    }
}
