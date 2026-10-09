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
package org.apache.tika.server.standard.writer;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.server.core.writer.AbstractTikaServerWriter;
import org.apache.tika.xmp.XMPMetadata;

/**
 * Writes {@link Metadata} as XMP, for exactly <code>application/rdf+xml</code>.
 */
public class XMPMessageBodyWriter extends AbstractTikaServerWriter<Metadata> {

    private static final MediaType RDF_XML = MediaType.valueOf("application/rdf+xml");

    public XMPMessageBodyWriter() {
        super(RDF_XML);
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return Metadata.class.isAssignableFrom(clazz);
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return RDF_XML.equals(mediaType) && supports(clazz);
    }

    @Override
    protected void writeInternal(Metadata metadata, HttpOutputMessage outputMessage) throws IOException {
        OutputStream entityStream = outputMessage.getBody();
        try {
            Writer writer = new OutputStreamWriter(entityStream, UTF_8);
            XMPMetadata xmp = new XMPMetadata(metadata);
            writer.write(xmp.toString());
            writer.flush();
        } catch (TikaException e) {
            throw new IOException(e);
        }
        entityStream.flush();
    }
}
