package com.basra.corbas;

import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.PyObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private Python py;
    private PyObject flaskModule;
    private Thread flaskThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Python
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        py = Python.getInstance();

        // Setup WebView
        webView = findViewById(R.id.webview);
        setupWebView();

        // Start Flask server in background thread
        startFlaskServer();

        // Load the HTML
        loadCorbasHTML();
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new WebAppInterface(), "Android");
    }

    private void startFlaskServer() {
        flaskThread = new Thread(() -> {
            try {
                // Import the Flask backend
                flaskModule = py.getModule("corbas_backend");
                
                // Start Flask (this will block)
                PyObject app = flaskModule.get("app");
                app.callAttr("run", "127.0.0.1", 5000, false, true);
                
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> 
                    Toast.makeText(this, "Error starting server: " + e.getMessage(), 
                                 Toast.LENGTH_LONG).show()
                );
            }
        });
        flaskThread.start();

        // Give Flask time to start
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void loadCorbasHTML() {
        try {
            InputStream is = getAssets().open("corbas.html");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String html = new String(buffer, StandardCharsets.UTF_8);
            
            // Load HTML with base URL pointing to localhost
            webView.loadDataWithBaseURL("http://127.0.0.1:5000/", html, 
                                        "text/html", "UTF-8", null);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error loading HTML: " + e.getMessage(), 
                         Toast.LENGTH_LONG).show();
        }
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> 
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show()
            );
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stop Flask server
        if (flaskThread != null && flaskThread.isAlive()) {
            flaskThread.interrupt();
        }
    }
}
