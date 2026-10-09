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

import java.io.IOException;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;

/**
 * Writes named byte arrays as a tar archive.
 */
public class TarWriter extends AbstractTikaServerWriter<Map<String, byte[]>> {

    public TarWriter() {
        super(MediaType.valueOf("application/x-tar"));
    }

    private static void tarStoreBuffer(TarArchiveOutputStream zip, String name, byte[] dataBuffer) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);

        entry.setSize(dataBuffer.length);

        zip.putArchiveEntry(entry);

        zip.write(dataBuffer);

        zip.closeArchiveEntry();
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return Map.class.isAssignableFrom(clazz);
    }

    @Override
    protected void writeInternal(Map<String, byte[]> parts, HttpOutputMessage outputMessage) throws IOException {
        TarArchiveOutputStream zip = new TarArchiveOutputStream(outputMessage.getBody());

        for (Map.Entry<String, byte[]> entry : parts.entrySet()) {
            tarStoreBuffer(zip, entry.getKey(), entry.getValue());
        }

        zip.close();
    }
}
