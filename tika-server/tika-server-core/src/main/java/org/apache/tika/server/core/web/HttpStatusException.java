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

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * An exception that carries the HTTP response it stands for. Unless a more specific
 * handler maps it, the server answers with that response: by default the status alone,
 * with no body.
 */
public class HttpStatusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ResponseEntity<?> response;

    public HttpStatusException(HttpStatus status) {
        this((String) null, status);
    }

    public HttpStatusException(String message, HttpStatus status) {
        super(message);
        this.response = ResponseEntity.status(status).build();
    }

    public HttpStatusException(String message) {
        this(message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public HttpStatusException(Throwable cause) {
        this(cause, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public HttpStatusException(Throwable cause, HttpStatus status) {
        super("HTTP " + status.value() + " " + status.getReasonPhrase(), cause);
        this.response = ResponseEntity.status(status).build();
    }

    /**
     * @param status  the status
     * @param headers response headers, such as <code>Allow</code>
     */
    public HttpStatusException(int status, HttpHeaders headers) {
        super((String) null);
        this.response = ResponseEntity.status(status).headers(headers).build();
    }

    /**
     * @param response the response, such as an error status with a message body
     */
    public HttpStatusException(ResponseEntity<?> response) {
        super(response.getStatusCode().toString());
        this.response = response;
    }

    /**
     * @return the response this exception stands for
     */
    public ResponseEntity<?> getResponse() {
        return response;
    }
}
