package com.android.volley;

public abstract class AsyncNetwork implements Network {

    // TODO: How to handle runtime exceptions?
    public interface OnRequestComplete {
        void onSuccess(NetworkResponse networkResponse);
        void onError(VolleyError volleyError);
    }

    public abstract void performRequest(Request<?> request, OnRequestComplete callback);

    @Override
    public NetworkResponse performRequest(Request<?> request) throws VolleyError {
        // TODO: Implement
        return null;
    }
}
