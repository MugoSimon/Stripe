package com.mugosimon.stripe;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.mugosimon.stripe.StaticData.StaticData;
import com.stripe.android.PaymentConfiguration;
import com.stripe.android.paymentsheet.PaymentSheet;
import com.stripe.android.paymentsheet.PaymentSheetResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Button buttonPayment;
    private String customerID;
    private String ephemeralKey;
    private String clientSecret;
    private PaymentSheet paymentSheet;
    private String publishableKey, secretKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate: Initializing StaticData");
        StaticData staticData = new StaticData();
        publishableKey = staticData.getPublishableKey();
        secretKey = staticData.getSecretKey();

        Log.d(TAG, "onCreate: Initializing PaymentConfiguration");
        PaymentConfiguration.init(this, publishableKey);

        Log.d(TAG, "onCreate: Initializing PaymentSheet");
        paymentSheet = new PaymentSheet(this, this::onPaymentResult);

        Log.d(TAG, "onCreate: Setting up payment button");
        buttonPayment = findViewById(R.id.payButton);
        buttonPayment.setOnClickListener(v -> paymentFlow());

        Log.d(TAG, "onCreate: Creating customer on Stripe");
        createCustomer();
    }

    private void createCustomer() {
        compositeDisposable.add(
                Flowable.fromCallable(() -> {
                            Log.d(TAG, "createCustomer: Making request to create customer");
                            String url = "https://api.stripe.com/v1/customers";
                            StringRequest request = new StringRequest(Request.Method.POST, url,
                                    this::handleCustomerCreationResponse,
                                    this::handleError
                            ) {
                                @Override
                                public Map<String, String> getHeaders() throws AuthFailureError {
                                    Map<String, String> headers = new HashMap<>();
                                    headers.put("Authorization", "Bearer " + secretKey);
                                    return headers;
                                }
                            };
                            sendRequest(request);
                            return true;
                        })
                        .subscribeOn(Schedulers.io())
                        .retryWhen(errors -> errors.flatMap((Function<Throwable, Flowable<Long>>) error ->
                                Flowable.timer(5, TimeUnit.SECONDS)))
                        .subscribe(
                                result -> Log.d(TAG, "createCustomer: Customer created successfully"),
                                error -> Log.e(TAG, "createCustomer: Error creating customer", error)
                        )
        );
    }

    private void handleCustomerCreationResponse(String response) {
        try {
            JSONObject object = new JSONObject(response);
            customerID = object.getString("id");
            Log.d(TAG, "handleCustomerCreationResponse: Customer ID: " + customerID);
            getEphemeralKey();
        } catch (JSONException e) {
            Log.e(TAG, "handleCustomerCreationResponse: JSON exception: " + e.getLocalizedMessage());
            showError(e.getLocalizedMessage());
        }
    }

    private void getEphemeralKey() {
        compositeDisposable.add(
                Flowable.fromCallable(() -> {
                            Log.d(TAG, "getEphemeralKey: Making request to get ephemeral key");
                            String url = "https://api.stripe.com/v1/ephemeral_keys";
                            StringRequest request = new StringRequest(Request.Method.POST, url,
                                    this::handleEphemeralKeyResponse,
                                    this::handleError
                            ) {
                                @Override
                                public Map<String, String> getHeaders() throws AuthFailureError {
                                    Map<String, String> headers = new HashMap<>();
                                    headers.put("Authorization", "Bearer " + secretKey);
                                    headers.put("Stripe-Version", "2023-10-16");
                                    return headers;
                                }

                                @Nullable
                                @Override
                                protected Map<String, String> getParams() throws AuthFailureError {
                                    Map<String, String> params = new HashMap<>();
                                    params.put("customer", customerID);
                                    return params;
                                }
                            };
                            sendRequest(request);
                            return true;
                        })
                        .subscribeOn(Schedulers.io())
                        .retryWhen(errors -> errors.flatMap((Function<Throwable, Flowable<Long>>) error ->
                                Flowable.timer(5, TimeUnit.SECONDS)))
                        .subscribe(
                                result -> Log.d(TAG, "getEphemeralKey: Ephemeral key fetched successfully"),
                                error -> Log.e(TAG, "getEphemeralKey: Error fetching ephemeral key", error)
                        )
        );
    }

    private void handleEphemeralKeyResponse(String response) {
        try {
            JSONObject object = new JSONObject(response);
            ephemeralKey = object.getString("id");
            Log.d(TAG, "handleEphemeralKeyResponse: Ephemeral key: " + ephemeralKey);
            getClientSecret();
        } catch (JSONException e) {
            Log.e(TAG, "handleEphemeralKeyResponse: JSON exception: " + e.getLocalizedMessage());
            showError(e.getLocalizedMessage());
        }
    }


    private void getClientSecret() {
        compositeDisposable.add(
                Completable.fromAction(() -> {
                            Log.d(TAG, "getClientSecret: Making request to get client secret");
                            String url = "https://api.stripe.com/v1/payment_intents";
                            StringRequest request = new StringRequest(Request.Method.POST, url,
                                    this::handleClientSecretResponse,
                                    this::handleError
                            ) {
                                @Override
                                public Map<String, String> getHeaders() throws AuthFailureError {
                                    Map<String, String> headers = new HashMap<>();
                                    headers.put("Authorization", "Bearer " + secretKey);
                                    return headers;
                                }

                                @Nullable
                                @Override
                                protected Map<String, String> getParams() throws AuthFailureError {
                                    Map<String, String> params = new HashMap<>();
                                    params.put("customer", customerID);
                                    params.put("amount", "10000"); // amount in cents
                                    params.put("currency", "USD");
                                    params.put("automatic_payment_methods[enabled]", "true");
                                    return params;
                                }
                            };
                            sendRequest(request);
                        })
                        .subscribeOn(Schedulers.io())
                        .retryWhen(errors -> errors.flatMap((Function<Throwable, Flowable<Long>>) error ->
                                Flowable.timer(5, TimeUnit.SECONDS)))
                        .subscribe(
                                () -> {
                                    Log.d(TAG, "getClientSecret: Client secret fetched successfully");
                                    runOnUiThread(() -> {
                                        // Proceed with the payment flow
                                        paymentFlow();
                                    });
                                },
                                error -> {
                                    Log.e(TAG, "getClientSecret: Error fetching client secret", error);
                                    runOnUiThread(() -> {
                                        showError(error.getLocalizedMessage());
                                    });
                                }
                        )
        );
    }


    private void handleClientSecretResponse(String response) {
        try {
            JSONObject object = new JSONObject(response);
            clientSecret = object.getString("client_secret");
            Log.d(TAG, "handleClientSecretResponse: Client secret: " + clientSecret);
        } catch (JSONException e) {
            Log.e(TAG, "handleClientSecretResponse: JSON exception: " + e.getLocalizedMessage());
            showError(e.getLocalizedMessage());
        }
    }


    private void paymentFlow() {
        if (clientSecret == null) {
            Log.w(TAG, "paymentFlow: Client secret is not ready");
            Toast.makeText(this, "Client secret is not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "paymentFlow: Presenting payment sheet");
        paymentSheet.presentWithPaymentIntent(
                clientSecret,
                new PaymentSheet.Configuration("Thimionii Big Giant",
                        new PaymentSheet.CustomerConfiguration(
                                customerID,
                                ephemeralKey
                        )
                )
        );
    }

    private void onPaymentResult(PaymentSheetResult paymentSheetResult) {
        if (paymentSheetResult instanceof PaymentSheetResult.Completed) {
            Log.d(TAG, "onPaymentResult: Payment success");
            Toast.makeText(this, "Payment Success", Toast.LENGTH_SHORT).show();
        } else {
            Log.d(TAG, "onPaymentResult: Payment failed");
            Toast.makeText(this, "Payment Failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendRequest(StringRequest request) {
        Log.d(TAG, "sendRequest: Adding request to queue");
        RequestQueue requestQueue = Volley.newRequestQueue(this);
        requestQueue.add(request);
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            Log.e(TAG, "showError: " + message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void handleError(VolleyError error) {
        Log.e(TAG, "handleError: " + error.getLocalizedMessage());
        showError(error.getLocalizedMessage());
    }

}
