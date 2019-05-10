/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.volley.toolbox;

import android.support.annotation.Nullable;

import com.android.volley.Header;
import com.android.volley.VolleyLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/** A response from an HTTP server. */
public final class HttpResponse {

    private final int mStatusCode;
    private final List<Header> mHeaders;
    private final int mContentLength;
    private final InputStream mContent;
    private final byte[] mContentBytes;

    /**
     * Construct a new HttpResponse for an empty response body.
     *
     * @param statusCode the HTTP status code of the response
     * @param headers the response headers
     */
    public HttpResponse(int statusCode, List<Header> headers) {
        this(statusCode, headers, /* contentLength= */ -1, /* content= */ null);
    }

    /**
     * Construct a new HttpResponse.
     *
     * @param statusCode the HTTP status code of the response
     * @param headers the response headers
     * @param contentLength the length of the response content. Ignored if there is no content.
     * @param content an {@link InputStream} of the response content. May be null to indicate that
     *     the response has no content.
     */
    public HttpResponse(
            int statusCode, List<Header> headers, int contentLength, InputStream content) {
        mStatusCode = statusCode;
        mHeaders = headers;
        mContentLength = contentLength;
        mContent = content;
        mContentBytes = null;
    }

    public HttpResponse(
            int statusCode, List<Header> headers, byte[] contentBytes) {
        mStatusCode = statusCode;
        mHeaders = headers;
        mContentLength = contentBytes.length;
        mContent = null;
        mContentBytes = contentBytes;
    }

    /** Returns the HTTP status code of the response. */
    public final int getStatusCode() {
        return mStatusCode;
    }

    /** Returns the response headers. Must not be mutated directly. */
    public final List<Header> getHeaders() {
        return Collections.unmodifiableList(mHeaders);
    }

    /** Returns the length of the content. Only valid if {@link #getContent} is non-null. */
    public final int getContentLength() {
        return mContentLength;
    }

    /**
     * Returns an {@link InputStream} of the response content. May be null to indicate that the
     * response has no content.
     */
    @Nullable
    public final InputStream getContent() {
        if (mContent == null && mContentBytes != null) {
            return new ByteArrayInputStream(mContentBytes);
        }
        return mContent;
    }

    @Nullable
    final byte[] getContentBytes(ByteArrayPool pool) throws IOException {
        if (mContentBytes != null) {
            return mContentBytes;
        }
        if (mContent == null) {
            return null;
        }
        return inputStreamToBytes(pool, mContent, mContentLength);
    }

    /** Reads the contents of an InputStream into a byte[]. */
    private static byte[] inputStreamToBytes(ByteArrayPool pool, InputStream in, int contentLength)
            throws IOException {
        PoolingByteArrayOutputStream bytes = new PoolingByteArrayOutputStream(pool, contentLength);
        byte[] buffer = null;
        try {
            buffer = pool.getBuf(1024);
            int count;
            while ((count = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        } finally {
            try {
                // Close the InputStream and release the resources by "consuming the content".
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                // This can happen if there was an exception above that left the stream in
                // an invalid state.
                VolleyLog.v("Error occurred when closing InputStream");
            }
            pool.returnBuf(buffer);
            bytes.close();
        }
    }
}
