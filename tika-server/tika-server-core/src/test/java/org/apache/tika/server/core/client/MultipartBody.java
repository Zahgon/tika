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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;


/**
 * A <code>multipart/form-data</code> request body of one or more parts, the first being the root.
 */
public final class MultipartBody {

    private final List<Attachment> parts;
    private final String boundary = "uuid:" + UUID.randomUUID();

    public MultipartBody(Attachment... parts) {
        this(Arrays.asList(parts));
    }

    public MultipartBody(List<Attachment> parts) {
        this.parts = List.copyOf(parts);
    }

    /**
     * @return the request content type, with this body's boundary
     */
    public String getContentType() {
        return "multipart/form-data; boundary=\"" + boundary + "\"";
    }

    void writeTo(OutputStream out) throws IOException {
        for (Attachment part : parts) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (String[] h : part.getHeaders()) {
                out.write((h[0] + ": " + h[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(part.getContent());
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
