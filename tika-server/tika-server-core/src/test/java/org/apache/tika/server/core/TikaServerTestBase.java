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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.Filter;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.converter.HttpMessageConverter;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.parser.digestutils.CommonsDigester;
import org.apache.tika.server.core.resource.TikaResource;
import org.apache.tika.server.core.resource.UnpackerResource;
import org.apache.tika.server.core.web.ExceptionMapper;
import org.apache.tika.server.core.web.ResponseFilter;
import org.apache.tika.server.core.web.TikaWebApplication;
import org.apache.tika.server.core.web.TikaWebSettings;

/**
 * Starts a tika-server web application with the resources and providers a test sets up, on
 * {@link #endPoint}, before each test, and stops it after.
 */
public abstract class TikaServerTestBase {
    protected static final int testPort = TestPortAllocator.findFreePort();
    protected static final String endPoint = "http://localhost:" + testPort;
    protected final static int DIGESTER_READ_LIMIT = 20 * 1024 * 1024;
    protected ConfigurableApplicationContext server;
    protected TikaConfig tika;

    public static void assertContains(String needle, String haystack) {
        assertTrue(haystack.contains(needle), needle + " not found in:\n" + haystack);
    }

    public static void assertNotFound(String needle, String haystack) {
        assertFalse(haystack.contains(needle), needle + " unexpectedly found in:\n" + haystack);
    }

    protected static InputStream copy(InputStream in, int remaining) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (remaining > 0) {
            byte[] bytes = new byte[remaining];
            int n = in.read(bytes);
            if (n <= 0) {
                break;
            }
            out.write(bytes, 0, n);
            remaining -= n;
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

    protected static String getStringFromInputStream(InputStream in) throws IOException {
        return IOUtils.toString(in, UTF_8);
    }

    public static InputStream gzip(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OutputStream gz = new GzipCompressorOutputStream(bos);
        IOUtils.copy(is, gz);
        gz.flush();
        gz.close();
        return new ByteArrayInputStream(bos.toByteArray());
    }

    protected static AverageColor getAverageColor(BufferedImage image, int minX, int maxX, int minY, int maxY) {
        long totalRed = 0;
        long totalGreen = 0;
        long totalBlue = 0;
        int pixels = 0;
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                int clr = image.getRGB(x, y);
                int red = (clr & 0x00ff0000) >> 16;
                int green = (clr & 0x0000ff00) >> 8;
                int blue = clr & 0x000000ff;
                totalRed += red;
                totalGreen += green;
                totalBlue += blue;
                pixels++;
            }
        }
        return new AverageColor((double) totalRed / (double) pixels, (double) totalGreen / (double) pixels, (double) totalBlue / (double) pixels);
    }

    @BeforeEach
    public void setUp() throws Exception {

        this.tika = new TikaConfig(getTikaConfigInputStream());
        TikaServerConfig tikaServerConfig = getTikaServerConfig();
        TikaResource.init(tika, tikaServerConfig, new CommonsDigester(DIGESTER_READ_LIMIT, "md5," + "sha1:32"), getInputStreamFactory(getTikaConfigInputStream()),
                new ServerStatus("", 0, true));
        TikaWebSettings sf = new TikaWebSettings().setComparator(new ProduceTypeResourceComparator());
        setUpResources(sf);
        setUpProviders(sf);
        server = TikaWebApplication.start(sf, Map.of("server.port", Integer.toString(testPort), "server.address", "localhost"));
    }

    protected TikaServerConfig getTikaServerConfig() {
        TikaServerConfig tikaServerConfig = new TikaServerConfig();
        tikaServerConfig.setReturnStackTrace(true);
        return tikaServerConfig;
    }

    protected InputStreamFactory getInputStreamFactory(InputStream tikaConfig) {
        return new DefaultInputStreamFactory();
    }

    protected InputStream getTikaConfigInputStream() throws IOException {
        return new ByteArrayInputStream(new String(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + "<properties>\n" + "    <parsers>\n" + "        <parser class=\"org.apache.tika.parser.DefaultParser\"/>\n" +
                        "    </parsers>\n" + "</properties>").getBytes(UTF_8));
    }

    /**
     * Have the test add its resources to {@link TikaWebSettings#getResources()}
     */
    protected abstract void setUpResources(TikaWebSettings sf);

    /**
     * Have the test add its writers, exception mappers and filters, if needed (see {@link #addProviders})
     */
    protected abstract void setUpProviders(TikaWebSettings sf);

    /**
     * Registers body writers, exception mappers and response filters by their kind.
     *
     * @param sf        the settings
     * @param providers the providers, in order
     */
    protected static void addProviders(TikaWebSettings sf, List<?> providers) {
        for (Object provider : providers) {
            if (provider instanceof HttpMessageConverter) {
                sf.getWriters().add((HttpMessageConverter<?>) provider);
            } else if (provider instanceof ExceptionMapper) {
                sf.getExceptionMappers().add((ExceptionMapper<?>) provider);
            } else if (provider instanceof ResponseFilter) {
                sf.getResponseFilters().add((ResponseFilter) provider);
            } else if (provider instanceof Filter) {
                sf.getRequestFilters().add((Filter) provider);
            } else {
                throw new IllegalArgumentException("unknown provider " + provider);
            }
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        server.close();
    }

    protected Map<String, String> readZipArchive(InputStream inputStream) throws IOException {
        Map<String, String> data = new HashMap<>();
        Path tempFile = null;
        try {
            tempFile = writeTemporaryArchiveFile(inputStream, "zip");
            try (ZipFile zip = ZipFile.builder().setPath(tempFile).get())
            {
                Enumeration<ZipArchiveEntry> entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    IOUtils.copy(zip.getInputStream(entry), bos);
                    data.put(entry.getName(), DigestUtils.md5Hex(bos.toByteArray()));
                }
            }
        } finally {
            if (tempFile != null) {
                Files.delete(tempFile);
            }
        }
        return data;
    }

    protected Map<String, byte[]> readZipArchiveBytes(InputStream inputStream) throws IOException {
        Map<String, byte[]> data = new HashMap<>();
        Path tempFile = null;
        try {
            tempFile = writeTemporaryArchiveFile(inputStream, "zip");
            try (ZipFile zip = ZipFile.builder().setPath(tempFile).get())
            {
                Enumeration<ZipArchiveEntry> entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    IOUtils.copy(zip.getInputStream(entry), bos);
                    data.put(entry.getName(), bos.toByteArray());
                }
            }
        } finally {
            if (tempFile != null) {
                Files.delete(tempFile);
            }
        }
        return data;
    }

    protected String readArchiveText(InputStream inputStream) throws IOException {
        Path tempFile = writeTemporaryArchiveFile(inputStream, "zip");
        ByteArrayOutputStream bos;
        try (ZipFile zip = ZipFile.builder().setPath(tempFile).get())
        {
            zip.getEntry(UnpackerResource.TEXT_FILENAME);
            bos = new ByteArrayOutputStream();
            IOUtils.copy(zip.getInputStream(zip.getEntry(UnpackerResource.TEXT_FILENAME)), bos);
        }
        Files.delete(tempFile);
        return bos.toString(UTF_8.name());
    }

    protected String readArchiveMetadataAndText(InputStream inputStream) throws IOException {
        Path tempFile = writeTemporaryArchiveFile(inputStream, "zip");
        String metadata;
        String txt;
        try (ZipFile zip = ZipFile.builder().setPath(tempFile).get())
        {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            zip.getEntry(UnpackerResource.META_FILENAME);
            IOUtils.copy(zip.getInputStream(zip.getEntry(UnpackerResource.META_FILENAME)), bos);
            metadata = new String(bos.toByteArray(), UTF_8);
            bos = new ByteArrayOutputStream();
            zip.getEntry(UnpackerResource.TEXT_FILENAME);
            IOUtils.copy(zip.getInputStream(zip.getEntry(UnpackerResource.TEXT_FILENAME)), bos);
            txt = new String(bos.toByteArray(), UTF_8);
        }
        Files.delete(tempFile);
        return metadata + "\n\n" + txt;
    }

    protected Map<String, String> readArchiveFromStream(ArchiveInputStream zip) throws IOException {
        Map<String, String> data = new HashMap<>();
        while (true) {
            ArchiveEntry entry = zip.getNextEntry();
            if (entry == null) {
                break;
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            IOUtils.copy(zip, bos);
            data.put(entry.getName(), DigestUtils.md5Hex(bos.toByteArray()));
        }

        return data;
    }

    private Path writeTemporaryArchiveFile(InputStream inputStream, String archiveType) throws IOException {
        Path tmp = Files.createTempFile("apache-tika-server-test-tmp-", "." + archiveType);
        Files.copy(inputStream, tmp, StandardCopyOption.REPLACE_EXISTING);
        return tmp;
    }

    /**
     * The average red, green and blue of a region of a rendered image.
     */
    public static class AverageColor {
        double red;
        double green;
        double blue;

        /**
         * @param averageRed   the average red component
         * @param averageGreen the average green component
         * @param averageBlue  the average blue component
         */
        public AverageColor(double averageRed, double averageGreen, double averageBlue) {
            this.red = averageRed;
            this.green = averageGreen;
            this.blue = averageBlue;
        }

        public double getRed() {
            return red;
        }

        public double getGreen() {
            return green;
        }

        public double getBlue() {
            return blue;
        }

        @Override
        public String toString() {
            return "AverageColor{" + "red=" + red + ", green=" + green + ", blue=" + blue + '}';
        }
    }
}
