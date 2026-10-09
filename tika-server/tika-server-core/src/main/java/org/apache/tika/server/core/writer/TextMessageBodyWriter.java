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
package org.apache.tika.server.core.writer;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.server.core.web.HttpStatusException;

/**
 * Writes the value of a single-entry {@link Metadata} as plain text, for exactly
 * <code>text/plain</code>.
 */
public class TextMessageBodyWriter extends AbstractTikaServerWriter<Metadata> {

    public TextMessageBodyWriter() {
        super(MediaType.TEXT_PLAIN);
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return Metadata.class.isAssignableFrom(clazz);
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return MediaType.TEXT_PLAIN.equals(mediaType) && supports(clazz);
    }

    @Override
    @SuppressWarnings("resource")
    protected void writeInternal(Metadata metadata, HttpOutputMessage outputMessage) throws IOException {
        if (metadata.names().length != 1) {
            throw new HttpStatusException("Metadata object must only have one entry!");
        }
        Writer writer = new OutputStreamWriter(outputMessage.getBody(), UTF_8);
        for (String name : metadata.names()) {
            writer.write(metadata.get(name));
        }
        // Don't close, just flush the stream
        writer.flush();
    }
}
