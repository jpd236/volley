/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.os.SystemClock;
import android.support.annotation.Nullable;

import com.android.volley.AsyncNetwork;
import com.android.volley.AuthFailureError;
import com.android.volley.Cache;
import com.android.volley.Cache.Entry;
import com.android.volley.ClientError;
import com.android.volley.Header;
import com.android.volley.NetworkError;
import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.RetryPolicy;
import com.android.volley.ServerError;
import com.android.volley.TimeoutError;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** A network performing Volley requests over an {@link HttpStack}. */
public class BasicNetwork extends AsyncNetwork {
    protected static final boolean DEBUG = VolleyLog.DEBUG;

    private static final int SLOW_REQUEST_THRESHOLD_MS = 3000;

    private static final int DEFAULT_POOL_SIZE = 4096;

    /**
     * @deprecated Should never have been exposed in the API. This field may be removed in a future
     *     release of Volley.
     */
    @Deprecated protected final HttpStack mHttpStack;

    private final BaseHttpStack mBaseHttpStack;

    protected final ByteArrayPool mPool;

    /**
     * @param httpStack HTTP stack to be used
     * @deprecated use {@link #BasicNetwork(BaseHttpStack)} instead to avoid depending on Apache
     *     HTTP. This method may be removed in a future release of Volley.
     */
    @Deprecated
    public BasicNetwork(HttpStack httpStack) {
        // If a pool isn't passed in, then build a small default pool that will give us a lot of
        // benefit and not use too much memory.
        this(httpStack, new ByteArrayPool(DEFAULT_POOL_SIZE));
    }

    /**
     * @param httpStack HTTP stack to be used
     * @param pool a buffer pool that improves GC performance in copy operations
     * @deprecated use {@link #BasicNetwork(BaseHttpStack, ByteArrayPool)} instead to avoid
     *     depending on Apache HTTP. This method may be removed in a future release of Volley.
     */
    @Deprecated
    public BasicNetwork(HttpStack httpStack, ByteArrayPool pool) {
        mHttpStack = httpStack;
        mBaseHttpStack = new AdaptedHttpStack(httpStack);
        mPool = pool;
    }

    /** @param httpStack HTTP stack to be used */
    public BasicNetwork(BaseHttpStack httpStack) {
        // If a pool isn't passed in, then build a small default pool that will give us a lot of
        // benefit and not use too much memory.
        this(httpStack, new ByteArrayPool(DEFAULT_POOL_SIZE));
    }

    /**
     * @param httpStack HTTP stack to be used
     * @param pool a buffer pool that improves GC performance in copy operations
     */
    public BasicNetwork(BaseHttpStack httpStack, ByteArrayPool pool) {
        mBaseHttpStack = httpStack;
        // Populate mHttpStack for backwards compatibility, since it is a protected field. However,
        // we won't use it directly here, so clients which don't access it directly won't need to
        // depend on Apache HTTP.
        mHttpStack = httpStack;
        mPool = pool;
    }

    private void onRequestSucceeded(Request<?> request, long requestStartMs, HttpResponse httpResponse,
                OnRequestComplete callback) {
        int statusCode = httpResponse.getStatusCode();
        List<Header> responseHeaders = httpResponse.getHeaders();
        // Handle cache validation.
        if (statusCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            Entry entry = request.getCacheEntry();
            if (entry == null) {
                callback.onSuccess(new NetworkResponse(
                        HttpURLConnection.HTTP_NOT_MODIFIED,
                        /* data= */ null,
                        /* notModified= */ true,
                        SystemClock.elapsedRealtime() - requestStartMs,
                        responseHeaders));
                return;
            }
            // Combine cached and response headers so the response will be complete.
            List<Header> combinedHeaders = combineHeaders(responseHeaders, entry);
            callback.onSuccess(new NetworkResponse(
                    HttpURLConnection.HTTP_NOT_MODIFIED,
                    entry.data,
                    /* notModified= */ true,
                    SystemClock.elapsedRealtime() - requestStartMs,
                    combinedHeaders));
        }

        // Some responses such as 204s do not have content.  We must check.
        // TODO: If the response needs to be read from the InputStream, submit it to the
        // blocking thread pool.
        byte[] responseContents;
        try {
            responseContents = httpResponse.getContentBytes(mPool);
        } catch (IOException e) {
            onRequestFailed(request, callback, e, requestStartMs, httpResponse, null);
            return;
        }
        if (responseContents == null) {
            // Add 0 byte response as a way of honestly representing a
            // no-content request.
            responseContents = new byte[0];
        }

        // if the request is slow, log it.
        long requestLifetime = SystemClock.elapsedRealtime() - requestStartMs;
        logSlowRequests(requestLifetime, request, responseContents, statusCode);

        if (statusCode < 200 || statusCode > 299) {
            onRequestFailed(request, callback, new IOException(), requestStartMs, httpResponse, responseContents);
        }
        callback.onSuccess(new NetworkResponse(
                statusCode,
                responseContents,
                /* notModified= */ false,
                SystemClock.elapsedRealtime() - requestStartMs,
                responseHeaders));
    }

    private void onRequestFailed(Request<?> request, OnRequestComplete callback, IOException exception, long requestStartMs, @Nullable HttpResponse httpResponse, @Nullable byte[] responseContents) {
        if (exception instanceof SocketTimeoutException) {
            attemptRetryOnException("socket", request, callback, new TimeoutError());
        } else if (exception instanceof MalformedURLException) {
            // TODO: Make sure this propagates correctly.
            throw new RuntimeException("Bad URL " + request.getUrl(), exception);
        } else {
            int statusCode;
            if (httpResponse != null) {
                statusCode = httpResponse.getStatusCode();
            } else {
                callback.onError(new NoConnectionError(exception));
                return;
            }
            VolleyLog.e("Unexpected response code %d for %s", statusCode, request.getUrl());
            NetworkResponse networkResponse;
            if (responseContents != null) {
                List<Header> responseHeaders;
                if (httpResponse != null) {
                    responseHeaders = httpResponse.getHeaders();
                } else {
                    responseHeaders = new ArrayList<>();
                }
                networkResponse =
                        new NetworkResponse(
                                statusCode,
                                responseContents,
                                /* notModified= */ false,
                                SystemClock.elapsedRealtime() - requestStartMs,
                                responseHeaders);
                if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED
                        || statusCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    attemptRetryOnException(
                            "auth", request, callback, new AuthFailureError(networkResponse));
                } else if (statusCode >= 400 && statusCode <= 499) {
                    // Don't retry other client errors.
                    callback.onError(new ClientError(networkResponse));
                } else if (statusCode >= 500 && statusCode <= 599) {
                    if (request.shouldRetryServerErrors()) {
                        attemptRetryOnException(
                                "server", request, callback, new ServerError(networkResponse));
                    } else {
                        callback.onError(new ServerError(networkResponse));
                    }
                } else {
                    // 3xx? No reason to retry.
                    callback.onError(new ServerError(networkResponse));
                }
            } else {
                attemptRetryOnException("network", request, callback, new NetworkError());
            }
        }
    }

    @Override
    public void performRequest(final Request<?> request, final OnRequestComplete callback) {
        final long requestStartMs = SystemClock.elapsedRealtime();
        // Gather headers.
        Map<String, String> additionalRequestHeaders = getCacheHeaders(request.getCacheEntry());
        if (mBaseHttpStack instanceof AsyncHttpStack) {
            AsyncHttpStack asyncStack = (AsyncHttpStack) mBaseHttpStack;
            asyncStack.executeRequest(request, additionalRequestHeaders, new AsyncHttpStack.OnRequestComplete() {
                @Override
                public void onSuccess(HttpResponse httpResponse) {
                    onRequestSucceeded(request, requestStartMs, httpResponse, callback);
                }

                @Override
                public void onAuthError(AuthFailureError authFailureError) {
                    callback.onError(authFailureError);
                }

                @Override
                public void onError(IOException ioException) {
                    onRequestFailed(request, callback, ioException, requestStartMs, null, null);
                }
            });
        } else {
            try {
                onRequestSucceeded(request, requestStartMs, mBaseHttpStack.executeRequest(request, additionalRequestHeaders), callback);
            } catch (AuthFailureError e) {
                callback.onError(e);
            } catch (IOException e) {
                onRequestFailed(request, callback, e, requestStartMs, null, null);
            }
        }
    }

    /** Logs requests that took over SLOW_REQUEST_THRESHOLD_MS to complete. */
    private void logSlowRequests(
            long requestLifetime, Request<?> request, byte[] responseContents, int statusCode) {
        if (DEBUG || requestLifetime > SLOW_REQUEST_THRESHOLD_MS) {
            VolleyLog.d(
                    "HTTP response for request=<%s> [lifetime=%d], [size=%s], "
                            + "[rc=%d], [retryCount=%s]",
                    request,
                    requestLifetime,
                    responseContents != null ? responseContents.length : "null",
                    statusCode,
                    request.getRetryPolicy().getCurrentRetryCount());
        }
    }

    /**
     * Attempts to prepare the request for a retry. If there are no more attempts remaining in the
     * request's retry policy, a timeout exception is thrown.
     *
     * @param request The request to use.
     */
    private void attemptRetryOnException(
            String logPrefix, Request<?> request, OnRequestComplete callback, VolleyError exception) {
        RetryPolicy retryPolicy = request.getRetryPolicy();
        int oldTimeout = request.getTimeoutMs();

        try {
            // TODO: In the future, we should support retrying after some delay if mBaseHttpStack is
            // an AsyncHttpStack. We can schedule a main-thread runnable after some timeout to call
            // performRequest. (This isn't safe to do if we don't have an AsyncHttpStack, as in this
            // case we'd block the main thread on network activity).
            retryPolicy.retry(exception);
        } catch (VolleyError e) {
            request.addMarker(
                    String.format("%s-timeout-giveup [timeout=%s]", logPrefix, oldTimeout));
            callback.onError(e);
            return;
        }
        request.addMarker(String.format("%s-retry [timeout=%s]", logPrefix, oldTimeout));
        performRequest(request, callback);
    }

    private Map<String, String> getCacheHeaders(Cache.Entry entry) {
        // If there's no cache entry, we're done.
        if (entry == null) {
            return Collections.emptyMap();
        }

        Map<String, String> headers = new HashMap<>();

        if (entry.etag != null) {
            headers.put("If-None-Match", entry.etag);
        }

        if (entry.lastModified > 0) {
            headers.put(
                    "If-Modified-Since", HttpHeaderParser.formatEpochAsRfc1123(entry.lastModified));
        }

        return headers;
    }

    protected void logError(String what, String url, long start) {
        long now = SystemClock.elapsedRealtime();
        VolleyLog.v("HTTP ERROR(%s) %d ms to fetch %s", what, (now - start), url);
    }

    /**
     * Converts Headers[] to Map&lt;String, String&gt;.
     *
     * @deprecated Should never have been exposed in the API. This method may be removed in a future
     *     release of Volley.
     */
    @Deprecated
    protected static Map<String, String> convertHeaders(Header[] headers) {
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < headers.length; i++) {
            result.put(headers[i].getName(), headers[i].getValue());
        }
        return result;
    }

    /**
     * Combine cache headers with network response headers for an HTTP 304 response.
     *
     * <p>An HTTP 304 response does not have all header fields. We have to use the header fields
     * from the cache entry plus the new ones from the response. See also:
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.5
     *
     * @param responseHeaders Headers from the network response.
     * @param entry The cached response.
     * @return The combined list of headers.
     */
    private static List<Header> combineHeaders(List<Header> responseHeaders, Entry entry) {
        // First, create a case-insensitive set of header names from the network
        // response.
        Set<String> headerNamesFromNetworkResponse = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (!responseHeaders.isEmpty()) {
            for (Header header : responseHeaders) {
                headerNamesFromNetworkResponse.add(header.getName());
            }
        }

        // Second, add headers from the cache entry to the network response as long as
        // they didn't appear in the network response, which should take precedence.
        List<Header> combinedHeaders = new ArrayList<>(responseHeaders);
        if (entry.allResponseHeaders != null) {
            if (!entry.allResponseHeaders.isEmpty()) {
                for (Header header : entry.allResponseHeaders) {
                    if (!headerNamesFromNetworkResponse.contains(header.getName())) {
                        combinedHeaders.add(header);
                    }
                }
            }
        } else {
            // Legacy caches only have entry.responseHeaders.
            if (!entry.responseHeaders.isEmpty()) {
                for (Map.Entry<String, String> header : entry.responseHeaders.entrySet()) {
                    if (!headerNamesFromNetworkResponse.contains(header.getKey())) {
                        combinedHeaders.add(new Header(header.getKey(), header.getValue()));
                    }
                }
            }
        }
        return combinedHeaders;
    }
}
