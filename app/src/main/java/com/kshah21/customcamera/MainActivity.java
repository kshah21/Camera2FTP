package com.kshah21.customcamera;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
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
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

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
    private String host = "ftp.dlptest.com";
    private String username="dlpuser@dlptest.com";
    private String password="hZ3Xr8alJPl8TtE";
    private int port = 21;
    private String destDirectory="";
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

    private long lastClickTime = 0;

    private static final String TAG = "TEST";
    private static final String TAG2 = "TEST2";

    private static final int REQUEST_CAMERA_PERMISSION = 256;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }


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

        ftpClient = new FTPWrapper();
    }

    /**
     * Check device characteristics for flash capabilities
     */
    private void checkDeviceChars(){
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
     * Begin bg thread and connect to FTP server
     */
    protected void onResume(){
        super.onResume();
        Log.i(TAG, "onResume");
        startBackgroundThread();
        if(textureView.isAvailable()){
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
            Log.i(TAG, "onSurfaceTextureAvailable: ");
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            //Do nothing for now
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
            //cameraID = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraID);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map!=null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];

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
            CameraCharacteristics camCharacteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Size[] jpegSizes = null;
            if(camCharacteristics != null){
                jpegSizes = camCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
            }
            int height = 3264;
            int width = 2448;
            if(jpegSizes != null && jpegSizes.length > 0){
                height = jpegSizes[0].getHeight();
                width = jpegSizes[0].getWidth();
            }
            
            ImageReader reader = ImageReader.newInstance(width,height,ImageFormat.JPEG,1);
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(reader.getSurface());
            outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequestBuilder.addTarget(reader.getSurface());
            
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION,ORIENTATIONS.get(rotation));

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
     * Switch current camera
     */
    public void switchCamera() {
        if (cameraID.equals(cameraFrontID)) {
            cameraID = cameraBackID;
            checkDeviceChars();
            closeCamera();
            reopenCamera();

        }
        else if (cameraID.equals(cameraBackID)) {
            cameraID = cameraFrontID;
            checkDeviceChars();
            closeCamera();
            reopenCamera();
        }
    }

    /**
     * Reopen camera after switching current camera
     */
    public void reopenCamera() {
        if (textureView.isAvailable()) {
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
                Bitmap scaledMap = Bitmap.createScaledBitmap(map, 3264, 2248, false);

                save(scaledMap);
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

        private void save(Bitmap scaledMap) throws IOException{
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            final String imageFileName = "/JPEG_" + timeStamp + "_" + ".jpg";

            final File file = new File(Environment.getExternalStorageDirectory()+imageFileName);
            System.out.println("TEST2 " + Environment.getExternalStorageDirectory()+imageFileName);
            OutputStream output = null;
            try{
                output = new FileOutputStream(file);
                scaledMap.compress(Bitmap.CompressFormat.JPEG,100,output);
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
