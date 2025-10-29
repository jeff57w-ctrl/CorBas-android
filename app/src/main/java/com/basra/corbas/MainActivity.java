package com.basra.corbas;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.PyObject;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IPdfTextLocation;
import com.itextpdf.kernel.pdf.canvas.parser.listener.RegexBasedLocationExtractionStrategy;
import com.itextpdf.kernel.pdf.annot.PdfAnnotation;
import com.itextpdf.kernel.pdf.annot.PdfTextMarkupAnnotation;
import com.tom.roush.pdfbox.android.PDFBoxResourceLoader;
import org.json.JSONArray;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int FILE_CHOOSER_REQUEST = 1;
    private static final int PERMISSION_REQUEST = 100;
    private WebView webView;
    private Python py;
    private Thread flaskThread;
    private ValueCallback<Uri[]> uploadMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize PDFBox for Android
        PDFBoxResourceLoader.init(getApplicationContext());

        // Check permissions
        checkPermissions();

        // Initialize Python
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        py = Python.getInstance();

        // Setup WebView
        webView = findViewById(R.id.webview);
        setupWebView();

        // Start Flask server
        startFlaskServer();

        // Load HTML
        loadCorbasHTML();
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, PERMISSION_REQUEST);
        }
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new PDFInterface(), "AndroidPDF");

        // Enable file uploads
        webView.setWebChromeClient(new WebChromeClient() {
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                           FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                }
                uploadMessage = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                
                startActivityForResult(Intent.createChooser(intent, "Select File"), FILE_CHOOSER_REQUEST);
                return true;
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (uploadMessage == null) return;
            
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        }
    }

    private void startFlaskServer() {
        flaskThread = new Thread(() -> {
            try {
                PyObject flaskModule = py.getModule("corbas_backend");
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
            String html = new String(buffer, "UTF-8");
            
            webView.loadDataWithBaseURL("http://127.0.0.1:5000/", html, 
                                        "text/html", "UTF-8", null);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error loading HTML: " + e.getMessage(), 
                         Toast.LENGTH_LONG).show();
        }
    }

    public class PDFInterface {
        @JavascriptInterface
        public String extractPDFText(String uriString) {
            try {
                Uri uri = Uri.parse(uriString);
                InputStream inputStream = getContentResolver().openInputStream(uri);
                
                PdfDocument pdfDoc = new PdfDocument(new PdfReader(inputStream));
                StringBuilder text = new StringBuilder();
                
                for (int i = 1; i <= pdfDoc.getNumberOfPages(); i++) {
                    text.append(PdfTextExtractor.getTextFromPage(pdfDoc.getPage(i)));
                    text.append("\n\n");
                }
                
                pdfDoc.close();
                inputStream.close();
                
                return text.toString();
                
            } catch (Exception e) {
                e.printStackTrace();
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String highlightPDF(String uriString, String phrasesJson, String color) {
            try {
                // Parse phrases
                JSONArray jsonArray = new JSONArray(phrasesJson);
                List<String> phrases = new ArrayList<>();
                for (int i = 0; i < jsonArray.length(); i++) {
                    phrases.add(jsonArray.getString(i).toLowerCase());
                }

                // Open PDF
                Uri uri = Uri.parse(uriString);
                InputStream inputStream = getContentResolver().openInputStream(uri);
                
                // Create output file
                File outputDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "CorBas");
                if (!outputDir.exists()) outputDir.mkdirs();
                
                String filename = "highlighted_" + System.currentTimeMillis() + ".pdf";
                File outputFile = new File(outputDir, filename);
                
                // Copy and highlight
                PdfDocument pdfDoc = new PdfDocument(
                    new PdfReader(inputStream),
                    new PdfWriter(outputFile)
                );

                int totalHighlights = 0;

                for (int pageNum = 1; pageNum <= pdfDoc.getNumberOfPages(); pageNum++) {
                    PdfPage page = pdfDoc.getPage(pageNum);
                    String pageText = PdfTextExtractor.getTextFromPage(page);
                    
                    for (String phrase : phrases) {
                        // Search for phrase (case-insensitive)
                        String lowerPageText = pageText.toLowerCase();
                        int index = lowerPageText.indexOf(phrase);
                        
                        while (index >= 0) {
                            // Create highlight annotation
                            // Note: This is simplified - exact positioning requires more complex logic
                            RegexBasedLocationExtractionStrategy strategy = 
                                new RegexBasedLocationExtractionStrategy(phrase);
                            
                            List<IPdfTextLocation> locations = 
                                strategy.getResultantLocations();
                            
                            for (IPdfTextLocation location : locations) {
                                Rectangle rect = location.getRectangle();
                                PdfTextMarkupAnnotation highlight = 
                                    PdfTextMarkupAnnotation.createHighLight(
                                        rect,
                                        new float[]{
                                            rect.getLeft(), rect.getBottom(),
                                            rect.getRight(), rect.getTop()
                                        }
                                    );
                                
                                highlight.setColor(ColorConstants.YELLOW);
                                page.addAnnotation(highlight);
                                totalHighlights++;
                            }
                            
                            index = lowerPageText.indexOf(phrase, index + 1);
                        }
                    }
                }

                pdfDoc.close();
                inputStream.close();

                return outputFile.getAbsolutePath() + "|" + totalHighlights;
                
            } catch (Exception e) {
                e.printStackTrace();
                return "ERROR: " + e.getMessage();
            }
        }

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
        if (flaskThread != null && flaskThread.isAlive()) {
            flaskThread.interrupt();
        }
    }
}
