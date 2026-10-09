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

/**
 * A {@link StreamingOutput} failed after writing part of the response body. When the entity had
 * flushed what it wrote, the response counts as sent (status and headers included) and that
 * part is all the client gets; otherwise the response of the failure follows what was written.
 */
public class PartialOutputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient byte[] output;
    private final boolean committed;

    public PartialOutputException(byte[] output, boolean committed, Throwable cause) {
        super(cause);
        this.output = output;
        this.committed = committed;
    }

    /**
     * @return what the entity wrote before it failed
     */
    public byte[] getOutput() {
        return output;
    }

    /**
     * @return true when the entity had flushed its output
     */
    public boolean isCommitted() {
        return committed;
    }
}
