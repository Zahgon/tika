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
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;

/**
 * Writes named byte arrays as a zip archive of stored (uncompressed) entries.
 */
public class ZipWriter extends AbstractTikaServerWriter<Map<String, byte[]>> {

    public ZipWriter() {
        super(MediaType.valueOf("application/zip"));
    }

    private static void zipStoreBuffer(ZipArchiveOutputStream zip, String name, byte[] dataBuffer) throws IOException {
        ZipEntry zipEntry = new ZipEntry(name != null ? name : UUID
                .randomUUID()
                .toString());
        zipEntry.setMethod(ZipOutputStream.STORED);

        zipEntry.setSize(dataBuffer.length);
        CRC32 crc32 = new CRC32();
        crc32.update(dataBuffer);
        zipEntry.setCrc(crc32.getValue());

        try {
            zip.putArchiveEntry(new ZipArchiveEntry(zipEntry));
        } catch (ZipException ex) {
            if (name != null) {
                zipStoreBuffer(zip, "x-" + name, dataBuffer);
                return;
            }
        }

        zip.write(dataBuffer);

        zip.closeArchiveEntry();
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return Map.class.isAssignableFrom(clazz);
    }

    @Override
    protected void writeInternal(Map<String, byte[]> parts, HttpOutputMessage outputMessage) throws IOException {
        ZipArchiveOutputStream zip = new ZipArchiveOutputStream(outputMessage.getBody());

        zip.setMethod(ZipArchiveOutputStream.STORED);

        for (Map.Entry<String, byte[]> entry : parts.entrySet()) {
            zipStoreBuffer(zip, entry.getKey(), entry.getValue());
        }

        zip.close();
    }
}
