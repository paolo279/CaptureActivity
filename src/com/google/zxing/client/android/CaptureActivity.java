/*
 * Copyright (C) 2008 ZXing authors
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

package com.google.zxing.client.android;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.camera.CameraManager;
import com.google.zxing.client.android.history.HistoryActivity;
import com.google.zxing.client.android.history.HistoryItem;
import com.google.zxing.client.android.history.HistoryManager;
import com.google.zxing.client.android.result.ResultButtonListener;
import com.google.zxing.client.android.result.ResultHandler;
import com.google.zxing.client.android.result.ResultHandlerFactory;
import com.google.zxing.client.android.result.supplement.SupplementalInfoRetriever;
import com.google.zxing.client.android.share.ShareActivity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the barcode correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback {

  private static final String TAG = CaptureActivity.class.getSimpleName();
  
  //valore per la densità
  float density = 0;

 

  private static final long DEFAULT_INTENT_RESULT_DURATION_MS = 1500L;
  private static final long BULK_MODE_SCAN_DELAY_MS = 1000L;

  private static final String PACKAGE_NAME = "com.google.zxing.client.android";
  private static final String PRODUCT_SEARCH_URL_PREFIX = "http://www.google";
  private static final String PRODUCT_SEARCH_URL_SUFFIX = "/m/products/scan";
  private static final String[] ZXING_URLS = { "http://zxing.appspot.com/scan", "zxing://scan/" };
  private static final String RETURN_CODE_PLACEHOLDER = "{CODE}";
  private static final String RETURN_URL_PARAM = "ret";

  public static final int HISTORY_REQUEST_CODE = 0x0000bacc;

  private static final Set<ResultMetadataType> DISPLAYABLE_METADATA_TYPES =
      EnumSet.of(ResultMetadataType.ISSUE_NUMBER,
                 ResultMetadataType.SUGGESTED_PRICE,
                 ResultMetadataType.ERROR_CORRECTION_LEVEL,
                 ResultMetadataType.POSSIBLE_COUNTRY);

  private CameraManager cameraManager;
  private CaptureActivityHandler handler;
  private Result savedResultToShow;
  private ViewfinderView viewfinderView;
  private TextView statusView;
  private View resultView;
  private Result lastResult;
  private boolean hasSurface;
  private boolean copyToClipboard;
  private IntentSource source;
  private String sourceUrl;
  private String returnUrlTemplate;
  private Collection<BarcodeFormat> decodeFormats;
  private String characterSet;
  private String versionName;
  //private HistoryManager historyManager;
  private InactivityTimer inactivityTimer;
  long start =0;

  private final DialogInterface.OnClickListener aboutListener =
      new DialogInterface.OnClickListener() {
    @Override
    public void onClick(DialogInterface dialogInterface, int i) {
      Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.zxing_url)));
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
      startActivity(intent);
    }
  };

  ViewfinderView getViewfinderView() {
    return viewfinderView;
  }

  public Handler getHandler() {
    return handler;
  }

  CameraManager getCameraManager() {
    return cameraManager;
  }

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);

    Window window = getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    setContentView(R.layout.capture);

    hasSurface = false;
   // historyManager = new HistoryManager(this);
   // historyManager.trimHistory();
    inactivityTimer = new InactivityTimer(this);
   // beepManager = new BeepManager(this);

    PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

  }

  @Override
  protected void onResume() {
    super.onResume();

    // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
    // want to open the camera driver and measure the screen size if we're going to show the help on
    // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
    // off screen.
    
    
    //qui crea l'oggetto CameraManager che si occupa di gestire la telecamera e il riquadro di scansione
    cameraManager = new CameraManager(getApplication());

    //qui viene gestita la view di quando viene trovata dell'activity con ViewFinderView che si occupa di disegnare 
    
    viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
    viewfinderView.setCameraManager(cameraManager);

    resultView = findViewById(R.id.result_view);
    statusView = (TextView) findViewById(R.id.status_view);

    handler = null;
    lastResult = null;

    //questo metodo serve per resettare la view
    resetStatusView();

    SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
    SurfaceHolder surfaceHolder = surfaceView.getHolder();
    
    surfaceHolder.addCallback(this);
    
    //if (hasSurface) {
      // The activity was paused but not stopped, so the surface still exists. Therefore
      // surfaceCreated() won't be called, so init the camera here.
    	
      //initCamera(surfaceHolder);
      
    //} else {
      // Install the callback and wait for surfaceCreated() to init the camera.
      
      //surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
   // }

   // beepManager.updatePrefs();

    // timer per spengere l'activity in caso di sleep
    inactivityTimer.onResume();

    //prende l'intent passata
    Intent intent = getIntent();

  
    source = IntentSource.NONE;
    decodeFormats = null;
    characterSet = null;
    
    
    //prende le azioni dell'activity e vede quali codici devono essere letti
    if (intent != null) {

    	
      String action = intent.getAction();

      if (Intents.Scan.ACTION.equals(action)) {

        // Scan the formats the intent requested, and return the result to the calling activity.
        source = IntentSource.NATIVE_APP_INTENT;
        decodeFormats = EnumSet.of(BarcodeFormat.QR_CODE); // DecodeFormatManager.parseDecodeFormats(intent);

       
        String customPromptMessage = intent.getStringExtra(Intents.Scan.PROMPT_MESSAGE);
        if (customPromptMessage != null) {
          statusView.setText(customPromptMessage);
        }

      } 

      characterSet = intent.getStringExtra(Intents.Scan.CHARACTER_SET);

    }
  }
  
 

  @Override
  protected void onPause() {
    if (handler != null) {
      handler.quitSynchronously();
      handler = null;
    }
    inactivityTimer.onPause();
    cameraManager.closeDriver();
    if (!hasSurface) {
      SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
      SurfaceHolder surfaceHolder = surfaceView.getHolder();
      surfaceHolder.removeCallback(this);
    }
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    inactivityTimer.shutdown();
    super.onDestroy();
  }

  

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    if (holder == null) {
      Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!");
    }
    if (!hasSurface) {
      hasSurface = true;
      initCamera(holder);
    }
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    hasSurface = false;
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

  }
  
  
  
  

  /**
   * A valid barcode has been found, so give an indication of success and show the results.
   *
   * @param rawResult The contents of the barcode.
   * @param barcode   A greyscale bitmap of the camera data which was decoded.
   */
  
  public void handleDecode(Result rawResult, Bitmap barcode) {
	  
	  // Questo metodo viene chiamato quando viene matchato quello che cerchiamo !!!
	  
	  
    inactivityTimer.onActivity();
    
    lastResult = rawResult; //rappresenta il risultato della ricerca
 
    	   ///BARCODE TROVATO E SETTO DENSITA' SE SO LE DIMENSIONI
        ResultPoint[] punti = rawResult.getResultPoints();
        
        if (rawResult.toString().equals("8032325104813")){
        	density = (punti[1].getX()-punti[0].getX())/3;
        	statusView.setText( rawResult.toString()+" Trovato a X: "+punti[0].getX()+" - Y:"+punti[0].getY()+" Densità: "+density+" px/cm");
        	
        }else
        statusView.setText( rawResult.toString()+" Trovato a X:"+punti[0].getX()+" - Y:"+punti[0].getY());
        
 
      
      //DISEGNA LE LINEE
     drawResultPoints(barcode, rawResult);
      
    viewfinderView.drawResultBitmap(barcode);
      
      
      
      restartPreviewAfterDelay(0);
      
      
      
   /*   viewfinderView.setOnClickListener(new OnClickListener() {
		
		@Override
		public void onClick(View v) {
			//restartTracking();
		    
			restartPreviewAfterDelay(500);
			//handler = new CaptureActivityHandler(CaptureActivity.this, decodeFormats, characterSet, cameraManager);
			//drawViewfinder();
		}
	});*/
      
     
      
      
    
  }

  /**
   * Superimpose a line for 1D or dots for 2D to highlight the key features of the barcode.
   *
   * @param barcode   A bitmap of the captured image.
   * @param rawResult The decoded results which contains the points to draw.
   */
  private void drawResultPoints(Bitmap barcode, Result rawResult) {
	  
    ResultPoint[] points = rawResult.getResultPoints();   //viene creato un array di result point dove è posizionato il tag !!
    if (points != null && points.length > 0) {	
    	
    	
    	
    	//NEI QRCODE VENGONO RESTITUITI I 3 PUNTI DOVE SONO PRESENTI I QUADRATI DI LIMITAZIONE	
    	
    	
    	
      Canvas canvas = new Canvas(barcode);
      Paint paint = new Paint();
      paint.setColor(getResources().getColor(R.color.result_image_border));
      paint.setStrokeWidth(3.0f);
      paint.setStyle(Paint.Style.STROKE);
      Rect border = new Rect(2, 2, barcode.getWidth() - 2, barcode.getHeight() - 2);
      canvas.drawRect(border, paint);

      paint.setColor(getResources().getColor(R.color.result_points));
      
      
    // nel caso del BARCODE viene disegnata la linea verde e la cambio in una freccia !!
     
        
        //disegna delle linee gialle a un centimetro
        if(density != 0){
        	 paint.setColor(getResources().getColor(R.color.possible_result_points));
        	 canvas.drawLine(points[0].getX(), points[0].getY(), points[0].getX()-density,points[0].getY(), paint);
        	 canvas.drawLine(points[0].getX(), points[0].getY(), points[0].getX(),points[0].getY()+density, paint);
        	 canvas.drawLine(points[0].getX(), points[0].getY(), points[0].getX(),points[0].getY()-density, paint);
        }
        
    
    	  //nel caso dei QRCODE disegna i 3 punti
    	  
        paint.setStrokeWidth(10.0f);
        for (ResultPoint point : points) {
          canvas.drawPoint(point.getX(), point.getY(), paint);
          
        }
      
    }
  }


  
  

  private void initCamera(SurfaceHolder surfaceHolder) {
    try {
      cameraManager.openDriver(surfaceHolder);
      
      // Creating the handler starts the preview, which can also throw a RuntimeException.
      if (handler == null) {
        handler = new CaptureActivityHandler(this, decodeFormats, characterSet, cameraManager);
      }
      //decodeOrStoreSavedBitmap(null, null);
    } catch (IOException ioe) {
      Log.w(TAG, ioe);
      //displayFrameworkBugMessageAndExit();
    } catch (RuntimeException e) {
      // Barcode Scanner has seen crashes in the wild of this variety:
      // java.?lang.?RuntimeException: Fail to connect to camera service
      Log.w(TAG, "Unexpected error initializing camera", e);
      displayFrameworkBugMessageAndExit();
    }
  }

  private void displayFrameworkBugMessageAndExit() {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(getString(R.string.app_name));
    builder.setMessage(getString(R.string.msg_camera_framework_bug));
    builder.setPositiveButton(R.string.button_ok, new FinishListener(this));
    builder.setOnCancelListener(new FinishListener(this));
    builder.show();
  }

  public void restartPreviewAfterDelay(long delayMS) {
    if (handler != null) {
      handler.sendEmptyMessageDelayed(R.id.restart_preview, delayMS);
    }
   // resetStatusView();
  }

  private void resetStatusView() {
    resultView.setVisibility(View.GONE);
    statusView.setText(R.string.msg_default_status);
    statusView.setVisibility(View.VISIBLE);
    viewfinderView.setVisibility(View.VISIBLE);
    lastResult = null;
  }

  public void drawViewfinder() {
    viewfinderView.drawViewfinder();
  }
}
