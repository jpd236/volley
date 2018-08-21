package com.android.volley.cronet;

import android.content.Context;
import android.support.annotation.NonNull;

import com.android.volley.Header;
import com.android.volley.Request;
import com.android.volley.toolbox.AsyncHttpStack;
import com.android.volley.toolbox.HttpResponse;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlRequest.Callback;
import org.chromium.net.UrlResponseInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// TODO: This is all a quick hack and needs a lot of cleanup, including smarter allocations and
// error handling.
public class CronetHttpStack extends AsyncHttpStack {

    private final CronetEngine mCronetEngine;
    private final Executor mCallbackExecutor;

    public CronetHttpStack(Context context) {
        mCronetEngine = new CronetEngine.Builder(context).build();
        mCallbackExecutor =
                new ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(@NonNull Runnable runnable) {
                                Thread t = Executors.defaultThreadFactory().newThread(runnable);
                                t.setName("Volley-CronetCallbackThread");
                                return t;
                            }
                        });
    }

    @Override
    public void executeRequest(
            Request<?> request,
            Map<String, String> additionalHeaders,
            final OnRequestComplete callback) {
        final ByteArrayOutputStream bytesReceived = new ByteArrayOutputStream();
        final WritableByteChannel receiveChannel = Channels.newChannel(bytesReceived);
        UrlRequest urlRequest = mCronetEngine.newUrlRequestBuilder(
                request.getUrl(),
                new Callback() {
                    @Override
                    public void onRedirectReceived(UrlRequest urlRequest, UrlResponseInfo urlResponseInfo, String s) throws Exception {
                        urlRequest.followRedirect();
                    }

                    @Override
                    public void onResponseStarted(UrlRequest urlRequest, UrlResponseInfo urlResponseInfo) throws Exception {
                        urlRequest.read(ByteBuffer.allocateDirect(102400));
                    }

                    @Override
                    public void onReadCompleted(UrlRequest urlRequest, UrlResponseInfo urlResponseInfo, ByteBuffer byteBuffer) throws Exception {
                        byteBuffer.flip();
                        try {
                            receiveChannel.write(byteBuffer);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        byteBuffer.clear();
                        urlRequest.read(byteBuffer);
                    }

                    @Override
                    public void onSucceeded(UrlRequest urlRequest, UrlResponseInfo urlResponseInfo) {
                        List<Header> headers = new ArrayList<>();
                        for (Map.Entry<String, String> header : urlResponseInfo.getAllHeadersAsList()) {
                            headers.add(new Header(header.getKey(), header.getValue()));
                        }
                        HttpResponse response = new HttpResponse(
                                urlResponseInfo.getHttpStatusCode(),
                                headers,
                                bytesReceived.toByteArray());
                        callback.onSuccess(response);
                    }

                    @Override
                    public void onFailed(UrlRequest urlRequest, UrlResponseInfo urlResponseInfo, CronetException e) {
                        // TODO: Invoke callback.onFailure if we get no response?
                        // What about callback.onAuthFailure?
                        List<Header> headers = new ArrayList<>();
                        for (Map.Entry<String, String> header : urlResponseInfo.getAllHeadersAsList()) {
                            headers.add(new Header(header.getKey(), header.getValue()));
                        }
                        HttpResponse response = new HttpResponse(
                                urlResponseInfo.getHttpStatusCode(),
                                headers,
                                bytesReceived.toByteArray());
                        callback.onSuccess(response);
                    }
                },
                mCallbackExecutor).build();
        urlRequest.start();
    }
}
