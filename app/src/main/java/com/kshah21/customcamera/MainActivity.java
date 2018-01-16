package com.kshah21.customcamera;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.view.ContextThemeWrapper;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import org.w3c.dom.Text;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private FloatingActionButton capture_button;
    private FloatingActionButton flash_button;
    private FloatingActionButton ftp_button;
    private FloatingActionButton cam_switch_button;
    private TextureView textureView;

    private FTPWrapper ftpClient;
    private String host;
    private String username;
    private String password;
    private int port = 21;
    private String destDirectory;
    private boolean connectedFTP=false;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;

    private Size imageDimension;
    private ImageReader imageReader;

    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    private Boolean flashSupported;
    private Boolean flashEnabled = false;

    private String cameraFrontID;
    private String cameraBackID;
    private String cameraID;

    private boolean isLandscape = false;
    private int deviceOrientation;
    private OrientationEventListener orientation_listener;

    private long lastClickTime = 0;

    private SharedPreferences sharedPref;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private static final String TAG = "TEST";
    private static final String TAG2 = "TEST2";

    private static final int REQUEST_CAMERA_PERMISSION = 256;


    /**
     * Bind views to fields and check device support
     */
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.i(TAG, "onCreate");

        textureView = (TextureView) findViewById(R.id.texture_view);
        capture_button = (FloatingActionButton) findViewById(R.id.capture_floating_button);
        flash_button = (FloatingActionButton) findViewById(R.id.flash_floating_button);
        ftp_button = (FloatingActionButton) findViewById(R.id.server_floating_button);
        cam_switch_button = (FloatingActionButton) findViewById(R.id.cam_switch_floating_button);

        textureView.setSurfaceTextureListener(texture_listener);
        capture_button.setOnClickListener(camera_button_listener);
        ftp_button.setOnClickListener(ftp_button_listener);
        cam_switch_button.setOnClickListener(cam_switch_button_listener);

        //Check back camera chars
        cameraID = cameraFrontID;
        checkDeviceChars();

        orientation_listener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                deviceOrientation = orientation;
            }
        };

        sharedPref = getPreferences(Context.MODE_PRIVATE);
        readStoredPref();

        ftpClient = new FTPWrapper();
    }

    /**
     * Check device characteristics for flash capabilities
     */
    private void checkDeviceChars(){
        //Obtain current orientation
        if(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE)
        {
            isLandscape = true;
            Log.i("Output", "Landscape ");
        }
        else{
            Log.i("Output", "Portrait ");
        }

        CameraManager manager = (CameraManager)getSystemService(CAMERA_SERVICE);
        CameraCharacteristics current_chars = null;
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars
                        = manager.getCameraCharacteristics(id);

                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);

                if (cameraBackID == null && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraBackID = id;
                    if(cameraID == null){
                        cameraID = cameraBackID;
                    }
                }
                if(cameraFrontID == null && facing !=null && facing == CameraCharacteristics.LENS_FACING_FRONT){
                    cameraFrontID = id;
                }
                if(cameraID.equals(id)){
                    current_chars = chars;
                }
            }

            //Check flash enabled for current camera
            if(current_chars!=null){
                if(current_chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)){
                    flashSupported = true;
                    //flashEnabled = false;
                    flash_button.setVisibility(View.VISIBLE);
                    flash_button.setOnClickListener(flash_button_listener);
                    Log.i(TAG, "onCreate: Flash Available in back");
                }
                else{
                    //flash_button.hide();
                    flash_button.setVisibility(View.INVISIBLE);
                    flashSupported = false;
                    //flashEnabled = false;
                    Log.i(TAG, "onCreate: Flash Not Available in back");
                    flash_button.setOnClickListener(null);
                }
                Log.d(TAG, "INFO_SUPPORTED_HARDWARE_LEVEL " + current_chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
            }

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Reads stored FTP preferences
     * Defaults to a test Berlin test server
     */
    private void readStoredPref(){
        host = sharedPref.getString("host",null);
        username = sharedPref.getString("username",null);
        password = sharedPref.getString("password",null);
        destDirectory = sharedPref.getString("directory", null);

        if(host == null){
            Log.i(TAG, "readStoredPref: initially null");
            host = "ftp.dlptest.com";
            username = "dlpuser";
            password = "hZ3Xr8alJPl8TtE";
            destDirectory = "";
            storeFTPPrefs();
        }
        else{
            Log.i(TAG, "readStoredPref: read from prefs");
        }

    }

    /**
     * Stores FTP preferences
     */
    private void storeFTPPrefs(){
        SharedPreferences.Editor editor =sharedPref.edit();
        editor.putString("host",host);
        editor.putString("username",username);
        editor.putString("password",password);
        editor.putString("directory", destDirectory);
        editor.commit();
    }

    /**
     * Begin bg thread and connect to FTP server
     */
    protected void onResume(){
        super.onResume();
        Log.i(TAG, "onResume");

        if(orientation_listener.canDetectOrientation()){
            orientation_listener.enable();
        }

        startBackgroundThread();

        if(textureView.isAvailable()){
            configureTransform(textureView.getWidth(),textureView.getHeight());
            openCamera();
        }
        else{
            textureView.setSurfaceTextureListener(texture_listener);
        }

       new Thread(new Runnable() {
            public void run() {
                boolean status = false;
                // host – your FTP address
                // username & password – for your secured login
                // 21 default gateway for FTP
                status = ftpClient.ftpConnect(host, username, password, 21);
                if (status == true) {
                    Log.i(TAG2, "Connection Success");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(),"Connection Successful",Toast.LENGTH_SHORT).show();
                        }
                    });
                    connectedFTP=true;
                    destDirectory = ftpClient.home;
                } else {
                    Log.i(TAG2, "Connection failed");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(),"Connection Unsuccessful",Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Open camera when surface becomes available
     */
    TextureView.SurfaceTextureListener texture_listener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            Log.i(TAG, "onSurfaceTextureAvailable: " + width + " x " + height);
            Size temp = new Size(width, height);
            imageDimension = temp;
            configureTransform(width,height);
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            //configureTransform(width,height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            Log.i(TAG, "onSurfaceTextureDestroyed: ");
            if(cameraDevice != null){
                closeCamera();
            }
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    /**
     * Open camera and check permissions
     */
    public void openCamera(){
        Log.i(TAG, "openCamera: ");
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try{
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraID);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map!=null;

            Log.i("Output Size: ", "Texture: " + imageDimension.getWidth() + " x " + imageDimension.getHeight());

            //Request Permissions
            if(ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)!= PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)!= PackageManager.PERMISSION_GRANTED){
                ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.CAMERA,Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraID,stateCallback,null);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    /**
     * Handle camera open events
     */
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.i(TAG, "onOpened");
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.i(TAG, "onDisconnected");
            cameraDevice.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.i(TAG, "onError");
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    /**
     * Create preview of camera
     */
    public void createCameraPreview(){
        try{
            Log.i(TAG, "createCameraPreview");
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture!=null;
            texture.setDefaultBufferSize(imageDimension.getWidth(),imageDimension.getHeight());
            Surface surface =  new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.i(TAG, "createCameraPreview - onConfigured: ");
                    if(cameraDevice==null){
                        return;
                    }
                    captureSession = session;
                    capture_button.setOnClickListener(camera_button_listener);
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                }

                public void onClosed(CameraCaptureSession session){
                    Log.i(TAG, "onClosed: Session closed");
                    capture_button.setOnClickListener(null);
                }
            },null);
        }
        catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    /**
     * Continuously update camera preview with continuous focus mode
     */
    public void updatePreview(){
        if(cameraDevice == null){
            //error
        }
        Log.i(TAG, "updatePreview");
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);
        try{
            captureSession.setRepeatingRequest(captureRequestBuilder.build(),null, backgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Capture current preview, handle flash, and take picture
     */
    private void takePicture(){
        Log.i(TAG, "takePicture");
        if(cameraDevice == null){
            return;
        }

        if(flashEnabled){
            Log.i(TAG, "takePicture: flash enabled");
            captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
            try {
                    captureSession.capture(captureRequestBuilder.build(), null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        else{
            Log.i(TAG, "takePicture: flash disabled");
            captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
            try {
                    captureSession.capture(captureRequestBuilder.build(), null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {

            int width = 3264;
            int height = 2448;
            ImageReader reader = ImageReader.newInstance(width,height,ImageFormat.JPEG,1);
            Log.i("Output Size: ", "Reader: " + reader.getWidth() + " x " + reader.getHeight());
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(reader.getSurface());
            outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequestBuilder.addTarget(reader.getSurface());

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraID);

            /*int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int orientation = getOrientation(rotation,sensorOrientation);*/

            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION,getJpegOrientation(characteristics,deviceOrientation));

            reader.setOnImageAvailableListener(readerListener,backgroundHandler);

            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.i(TAG, "takePicture - onConfigured");
                    try {
                            session.capture(captureRequestBuilder.build(),captureListener,backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                }
            },backgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Obtain correct orientation for jpeg picture based upon device rotation state
     */
    private int getOrientation(int rotation, int sensorOrientation) {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360;
    }

    /**
     * Obtain correct orientation for jpeg picture based upon device rotation state - second version
     */
    private int getJpegOrientation(CameraCharacteristics c, int devOrientation) {
        if (devOrientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return 0;
        int sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);

        // Round device orientation to a multiple of 90
        devOrientation = (devOrientation + 45) / 90 * 90;

        // Reverse device orientation for front-facing cameras
        boolean facingFront = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
        if (facingFront) devOrientation = -devOrientation;

        // Calculate desired JPEG orientation relative to camera orientation to make
        // the image upright relative to the device orientation
        int jpegOrientation = (sensorOrientation + devOrientation + 360) % 360;

        return jpegOrientation;
    }

    /**
     * Transform textureView in case of orientation change
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == textureView || null == imageDimension) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, imageDimension.getHeight(), imageDimension.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / imageDimension.getHeight(),
                    (float) viewWidth / imageDimension.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    /**
     * Switch current camera
     */
    public void switchCamera() {
        if (cameraID.equals(cameraFrontID)) {
            cameraID = cameraBackID;
            checkDeviceChars();
            closeCamera();
            reopenCamera();
            Log.i(TAG, "switchCamera: Now on back");

        }
        else if (cameraID.equals(cameraBackID)) {
            cameraID = cameraFrontID;
            checkDeviceChars();
            closeCamera();
            reopenCamera();
            Log.i(TAG, "switchCamera: Now on front");
        }
    }

    /**
     * Reopen camera after switching current camera
     */
    public void reopenCamera() {
        if (textureView.isAvailable()) {
            configureTransform(textureView.getWidth(),textureView.getHeight());
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(texture_listener);
        }
    }

    /**
     * Once taken image is available save it
     */
    ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.i(TAG, "onImageAvailable");
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);

                Bitmap map = BitmapFactory.decodeByteArray(bytes,0,bytes.length,null);

                if(!isLandscape){
                    Log.i("Orientation", "onImageAvailable: Portrait");
                    Matrix mtx = new Matrix();
                    if(cameraID == cameraFrontID){
                        Log.i("orientation", "onImageAvailable: camera front");
                        mtx.postRotate(-90);
                    }
                    else{
                        Log.i("orientation", "onImageAvailable: camera back");
                        mtx.postRotate(90);
                    }
                    Bitmap rotated = Bitmap.createBitmap(map,0,0,map.getWidth(),map.getHeight(),mtx,true);
                    save(rotated);
                }
                else{
                    save(map);
                }
            }
            catch (FileNotFoundException e){
                e.printStackTrace();
            }
            catch (IOException e){
                e.printStackTrace();
            }
            finally {
                if(image!=null){
                    image.close();
                }
            }
        }

        private void save(Bitmap map) throws IOException{
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            final String imageFileName = "/JPEG_" + timeStamp + "_" + ".jpg";

            final File file = new File(Environment.getExternalStorageDirectory()+imageFileName);
            OutputStream output = null;
            try{
                output = new FileOutputStream(file);
                map.compress(Bitmap.CompressFormat.JPEG,100,output);
                output.flush();
            }
            finally{
                if(output!=null){
                    output.close();
                }
            }
            new Thread(new Runnable() {
                public void run() {
                    boolean status = ftpClient.ftpUpload(Environment.getExternalStorageDirectory()+imageFileName,destDirectory+imageFileName);
                    if(status){
                        Log.i(TAG2, "Uploaded Successfully");
                    }
                    if(file.exists()){
                        file.delete();
                    }
                }
            }).start();
        }
    };

    /**
     * Recreate camera preview once capture is completed
     */
    final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Log.i(TAG, "onCaptureCompleted");
            createCameraPreview();
        }
    };

    /**
     * Starts bg thread and its respective handler
     */
    private void startBackgroundThread(){
        Log.i(TAG, "startBackgroundThread");
        backgroundThread = new HandlerThread("Camera Background");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /**
     * Stops background thread and its respective handler
     */
    private void stopBackgroundThread(){
        Log.i(TAG, "stopBackgroundThread");
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Close camera
     */
    public void closeCamera(){
        if(cameraDevice != null){
            cameraDevice.close();
            cameraDevice = null;
        }
        if(imageReader != null){
            imageReader.close();
            imageReader = null;
        }
    }

    /**
     * Handle permissions requests
     */
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(requestCode == REQUEST_CAMERA_PERMISSION){
            if(grantResults[0] == PackageManager.PERMISSION_DENIED){
                finish();
            }
        }
    }

    /**
     * Stop bg thread and disconnect from FTP server
     */
    protected void onPause(){
        Log.i(TAG, "onPause");
        orientation_listener.disable();
        stopBackgroundThread();
        super.onPause();
        new Thread(new Runnable() {
            public void run() {
                ftpClient.ftpDisconnect();
                connectedFTP=false;
            }
        }).start();
    }

    /**
     * If connected to FTP will allow take picture
     */
    View.OnClickListener camera_button_listener = new View.OnClickListener(){

        @Override
        public void onClick(View v) {
            if(connectedFTP){
                if (SystemClock.elapsedRealtime() - lastClickTime < 300){
                    return;
                }
                lastClickTime = SystemClock.elapsedRealtime();
                capture_button.setOnClickListener(null);
                takePicture();
            }
            else{
                Toast.makeText(getApplicationContext(), "Not Connected to FTP Server - Check Info", Toast.LENGTH_SHORT).show();
            }
        }
    };

    /**
     * Toggle flash
     */
    View.OnClickListener flash_button_listener = new View.OnClickListener(){

        @Override
        public void onClick(View v) {
            if(!flashEnabled){
                Log.i(TAG, "onClick: flash enabled");
                flashEnabled =true;
                flash_button.setImageDrawable(ContextCompat.getDrawable(getApplicationContext(),R.drawable.ic_flash_on_white_24dp));
            }
            else if(flashEnabled){
                Log.i(TAG, "onClick: flash disabled");
                flashEnabled = false;
                flash_button.setImageDrawable(ContextCompat.getDrawable(getApplicationContext(),R.drawable.ic_flash_off_white_24dp));
            }
        }
    };

    View.OnClickListener cam_switch_button_listener = new View.OnClickListener(){

        @Override
        public void onClick(View v) {
            switchCamera();
        }
    };

    /**
     * Handle FTP information changes via dialog box
     */
    View.OnClickListener ftp_button_listener = new View.OnClickListener(){

        @Override
        public void onClick(View v) {
            // get prompts.xml view
            LayoutInflater li = LayoutInflater.from(getApplicationContext());
            View promptsView = li.inflate(R.layout.ftp_prompt, null);

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(MainActivity.this);

            // set prompts.xml to alertdialog builder
            alertDialogBuilder.setView(promptsView);

            final EditText endpoint = (EditText) promptsView.findViewById(R.id.ftp_box_endpoint);
            final EditText user = (EditText) promptsView.findViewById(R.id.ftp_box_user);
            final EditText pass = (EditText) promptsView.findViewById(R.id.ftp_box_password);
            final EditText dest = (EditText) promptsView.findViewById(R.id.ftp_box_dest);

            endpoint.setText(host);
            user.setText(username);
            pass.setText(password);
            dest.setText(destDirectory);

            // set dialog message
            alertDialogBuilder
                    .setCancelable(false)
                    .setPositiveButton("OK",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,int id) {
                                    // get user input and set it to endpoint
                                    String temp_host = (endpoint.getText()).toString();
                                    String temp_username = (user.getText()).toString();
                                    String temp_password = (pass.getText()).toString();
                                    String temp_destDirectory = (dest.getText()).toString();

                                    endpoint.setText(temp_host);
                                    user.setText(temp_username);
                                    pass.setText(temp_password);
                                    dest.setText(temp_destDirectory);

                                    if((!temp_host.equals(host)) || (!temp_username.equals(username)) ||
                                            (!temp_password.equals(password))){
                                        host = temp_host;
                                        username = temp_username;
                                        password = temp_password;

                                        new Thread(new Runnable() {
                                            public void run() {
                                                if(connectedFTP){
                                                    ftpClient.ftpDisconnect();
                                                    connectedFTP=false;
                                                }
                                                boolean status = false;
                                                status = ftpClient.ftpConnect(host,username,password,port);
                                                if(status){
                                                    storeFTPPrefs();
                                                    runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            Toast.makeText(getApplicationContext(),"Connection Successful",Toast.LENGTH_SHORT).show();
                                                            connectedFTP=true;
                                                        }
                                                    });
                                                }
                                                else{
                                                    runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            Toast.makeText(getApplicationContext(),"Connection Unsuccessful - Check Info",Toast.LENGTH_SHORT).show();
                                                        }
                                                    });
                                                }
                                            }
                                        }).start();


                                    }
                                    else if(!temp_destDirectory.equals(destDirectory)){
                                        destDirectory = temp_destDirectory;
                                        new Thread(new Runnable() {
                                            public void run() {
                                                boolean status = false;
                                                status = ftpClient.changeWorkingDirectory(destDirectory);
                                                if(status){
                                                    storeFTPPrefs();
                                                    runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            Toast.makeText(getApplicationContext(),"Change Successful",Toast.LENGTH_SHORT).show();
                                                        }
                                                    });
                                                }
                                                else{
                                                    runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            Toast.makeText(getApplicationContext(),"Change Unsuccessful - Check Directory",Toast.LENGTH_SHORT).show();
                                                        }
                                                    });
                                                }
                                            }
                                        }).start();
                                    }
                                }
                            })
                    .setNegativeButton("Cancel",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,int id) {
                                    dialog.cancel();
                                }
                            });

            // create alert dialog
            AlertDialog alertDialog = alertDialogBuilder.create();

            // show it
            alertDialog.show();
        }
    };

} //End MainActivity
