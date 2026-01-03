package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.MainActivity.touchCharInput;
import static net.kdt.pojavlaunch.utils.MCOptionUtils.getMcScale;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;
import static org.lwjgl.glfw.CallbackBridge.windowHeight;
import static org.lwjgl.glfw.CallbackBridge.windowWidth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Environment;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.gamepad.DefaultDataProvider;
import net.kdt.pojavlaunch.customcontrols.gamepad.Gamepad;
import net.kdt.pojavlaunch.customcontrols.gamepad.direct.DirectGamepad;
import net.kdt.pojavlaunch.customcontrols.gamepad.direct.DirectGamepadEnableHandler;
import net.kdt.pojavlaunch.customcontrols.mouse.AbstractTouchpad;
import net.kdt.pojavlaunch.customcontrols.mouse.AndroidPointerCapture;
import net.kdt.pojavlaunch.customcontrols.mouse.InGUIEventProcessor;
import net.kdt.pojavlaunch.customcontrols.mouse.InGameEventProcessor;
import net.kdt.pojavlaunch.customcontrols.mouse.TouchEventProcessor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.MCOptionUtils;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import fr.spse.gamepad_remapper.GamepadHandler;
import fr.spse.gamepad_remapper.RemapperManager;
import fr.spse.gamepad_remapper.RemapperView;

/**
 * Class dealing with showing minecraft surface and taking inputs to dispatch them to minecraft
 */
public class MinecraftGLSurface extends View implements GrabListener, DirectGamepadEnableHandler {
    /* Gamepad object for gamepad inputs, instantiated on need */
    private GamepadHandler mGamepadHandler;
    /* The RemapperView.Builder object allows you to set which buttons to remap */
    private final RemapperManager mInputManager = new RemapperManager(getContext(), new RemapperView.Builder(null)
            .remapA(true)
            .remapB(true)
            .remapX(true)
            .remapY(true)

            .remapLeftJoystick(true)
            .remapRightJoystick(true)
            .remapStart(true)
            .remapSelect(true)
            .remapLeftShoulder(true)
            .remapRightShoulder(true)
            .remapLeftTrigger(true)
            .remapRightTrigger(true)
            .remapDpad(true));

    /* Sensitivity, adjusted according to screen size */
    private final double mSensitivityFactor = (1.4 * (1080f/ Tools.getDisplayMetrics((Activity) getContext()).heightPixels));

    /* Surface ready listener, used by the activity to launch minecraft */
    SurfaceReadyListener mSurfaceReadyListener = null;
    final Object mSurfaceReadyListenerLock = new Object();
    /* View holding the surface, either a SurfaceView or a TextureView */
    View mSurface;

    private final InGameEventProcessor mIngameProcessor = new InGameEventProcessor(mSensitivityFactor);
    private final InGUIEventProcessor mInGUIProcessor = new InGUIEventProcessor();
    private TouchEventProcessor mCurrentTouchProcessor = mInGUIProcessor;
    private AndroidPointerCapture mPointerCapture;
    private boolean mLastGrabState = false;
    
    /* 新增：鼠标控制相关 */
    private float mLastMouseX = 0, mLastMouseY = 0;
    private float mCenterX = 0, mCenterY = 0;
    private boolean mIsFirstMouseMove = true;
    private PointerIcon mTransparentPointerIcon = null;
    private boolean mIsCursorHidden = false;
    
    /* 日志 */
    private static final String LOG_TAG = "MinecraftGLSurface";
    private static final String LOG_FILE_PATH = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS) + "/minecraft_mouse_log.txt";

    public MinecraftGLSurface(Context context) {
        this(context, null);
    }

    public MinecraftGLSurface(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setFocusable(true);
        CallbackBridge.setDirectGamepadEnableHandler(this);
        
        // 初始化鼠标控制
        initMouseControl();
        initLogFile();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void setUpPointerCapture(AbstractTouchpad touchpad) {
        if(mPointerCapture != null) mPointerCapture.detach();
        mPointerCapture = new AndroidPointerCapture(touchpad, this);
    }

    /** Initialize the view and all its settings
     * @param isAlreadyRunning set to true to tell the view that the game is already running
     *                         (only updates the window without calling the start listener)
     * @param touchpad the optional cursor-emulating touchpad, used for touch event processing
     *                 when the cursor is not grabbed
     */
    public void start(boolean isAlreadyRunning, AbstractTouchpad touchpad){
        logMessage("start called, isAlreadyRunning: " + isAlreadyRunning);
        if(Tools.isAndroid8OrHigher()) setUpPointerCapture(touchpad);
        mInGUIProcessor.setAbstractTouchpad(touchpad);
        if(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE){
            SurfaceView surfaceView = new SurfaceView(getContext());
            mSurface = surfaceView;

            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                private boolean isCalled = isAlreadyRunning;
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    if(isCalled) {
                        JREUtils.setupBridgeWindow(surfaceView.getHolder().getSurface());
                        return;
                    }
                    isCalled = true;

                    realStart(surfaceView.getHolder().getSurface());
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                    refreshSize();
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {}
            });

            ((ViewGroup)getParent()).addView(surfaceView);
        }else{
            TextureView textureView = new TextureView(getContext());
            textureView.setOpaque(true);
            textureView.setAlpha(1.0f);
            mSurface = textureView;

            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                private boolean isCalled = isAlreadyRunning;
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    Surface tSurface = new Surface(surface);
                    if(isCalled) {
                        JREUtils.setupBridgeWindow(tSurface);
                        return;
                    }
                    isCalled = true;

                    realStart(tSurface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    refreshSize();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });

            ((ViewGroup)getParent()).addView(textureView);
        }
    }

    /**
     * The touch event for both grabbed an non-grabbed mouse state on the touch screen
     * Does not cover the virtual mouse touchpad
     */
    @Override
    @SuppressWarnings("accessibility")
    public boolean onTouchEvent(MotionEvent e) {
        // Kinda need to send this back to the layout
        if(((ControlLayout)getParent()).getModifiable()) return false;

        // Looking for a mouse to handle, won't have an effect if no mouse exists.
        for (int i = 0; i < e.getPointerCount(); i++) {
            int toolType = e.getToolType(i);
            if(toolType == MotionEvent.TOOL_TYPE_MOUSE) {
                logMessage("Mouse detected in onTouchEvent, pointer: " + i);
                if(Tools.isAndroid8OrHigher() &&
                        mPointerCapture != null) {
                    mPointerCapture.handleAutomaticCapture();
                    return true;
                }
            }else if(toolType != MotionEvent.TOOL_TYPE_STYLUS) continue;

            // Mouse found
            if(CallbackBridge.isGrabbing()) return false;
            CallbackBridge.sendCursorPos(e.getX(i) * LauncherPreferences.PREF_SCALE_FACTOR, 
                                        e.getY(i) * LauncherPreferences.PREF_SCALE_FACTOR);
            return true; //mouse event handled successfully
        }
        if (mIngameProcessor == null || mInGUIProcessor == null) return true;
        return mCurrentTouchProcessor.processTouchEvent(e);
    }

    private void createGamepad(View contextView, InputDevice inputDevice) {
        if(CallbackBridge.sGamepadDirectInput) {
            mGamepadHandler = new DirectGamepad();
        }else {
            mGamepadHandler = new Gamepad(contextView, inputDevice, DefaultDataProvider.INSTANCE, true);
        }
    }

    /**
     * The event for mouse/joystick movements
     */
    @SuppressLint("NewApi")
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int mouseCursorIndex = -1;

        logMessage("dispatchGenericMotionEvent - Action: " + event.getActionMasked() + 
                  ", Source: " + event.getSource() + ", PointerCount: " + event.getPointerCount());

        if(Gamepad.isGamepadEvent(event)){
            if(mGamepadHandler == null) createGamepad(this, event.getDevice());
            mInputManager.handleMotionEventInput(getContext(), event, mGamepadHandler);
            return true;
        }

        // 寻找鼠标指针
        for(int i = 0; i < event.getPointerCount(); i++) {
            int toolType = event.getToolType(i);
            if(toolType != MotionEvent.TOOL_TYPE_MOUSE && toolType != MotionEvent.TOOL_TYPE_STYLUS) continue;
            mouseCursorIndex = i;
            logMessage("Found mouse pointer at index: " + i + ", toolType: " + toolType);
            break;
        }
        if(mouseCursorIndex == -1) {
            logMessage("No mouse pointer found");
            return false;
        }

        // 更新抓取状态
        updateGrabState(CallbackBridge.isGrabbing());

        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
                float currentX = event.getX(mouseCursorIndex);
                float currentY = event.getY(mouseCursorIndex);
                
                logMessage("ACTION_HOVER_MOVE - Current: (" + currentX + ", " + currentY + 
                          "), isGrabbing: " + CallbackBridge.isGrabbing());
                
                if (CallbackBridge.isGrabbing()) {
                    // 抓取模式：使用相对移动
                    handleGrabbedMouseMovement(currentX, currentY);
                    return true;
                } else {
                    // 非抓取模式：使用绝对位置
                    float scaledX = currentX * LauncherPreferences.PREF_SCALE_FACTOR;
                    float scaledY = currentY * LauncherPreferences.PREF_SCALE_FACTOR;
                    CallbackBridge.mouseX = scaledX;
                    CallbackBridge.mouseY = scaledY;
                    CallbackBridge.sendCursorPos(scaledX, scaledY);
                    return true;
                }
                
            case MotionEvent.ACTION_SCROLL:
                logMessage("ACTION_SCROLL - hscroll: " + event.getAxisValue(MotionEvent.AXIS_HSCROLL) + 
                          ", vscroll: " + event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                CallbackBridge.sendScroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL), 
                                         event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                return true;
                
            case MotionEvent.ACTION_BUTTON_PRESS:
                logMessage("ACTION_BUTTON_PRESS - button: " + event.getActionButton());
                return sendMouseButtonUnconverted(event.getActionButton(), true);
                
            case MotionEvent.ACTION_BUTTON_RELEASE:
                logMessage("ACTION_BUTTON_RELEASE - button: " + event.getActionButton());
                return sendMouseButtonUnconverted(event.getActionButton(), false);
                
            default:
                logMessage("Unhandled motion event action: " + event.getActionMasked());
                return false;
        }
    }
    
    /** 处理抓取模式下的鼠标移动 */
    private void handleGrabbedMouseMovement(float currentX, float currentY) {
        if (mIsFirstMouseMove) {
            // 第一次移动，初始化位置
            mLastMouseX = currentX;
            mLastMouseY = currentY;
            mCenterX = getWidth() / 2f;
            mCenterY = getHeight() / 2f;
            mIsFirstMouseMove = false;
            logMessage("First mouse move in grab mode, initialized");
            return;
        }
        
        // 计算相对移动增量
        float deltaX = (currentX - mLastMouseX) * (float)mSensitivityFactor * 2.0f;
        float deltaY = (currentY - mLastMouseY) * (float)mSensitivityFactor * 2.0f;
        
        logMessage("Grab mode movement - Delta: (" + deltaX + ", " + deltaY + 
                  "), Last: (" + mLastMouseX + ", " + mLastMouseY + ")");
        
        // 使用CallbackBridge的相对移动方法
        CallbackBridge.sendRelativeMouseMovement(deltaX, deltaY);
        
        // 更新最后位置
        mLastMouseX = currentX;
        mLastMouseY = currentY;
        
        // 检查是否接近边缘，如果是则尝试重置
        if (Math.abs(currentX - mCenterX) > getWidth() * 0.4f || 
            Math.abs(currentY - mCenterY) > getHeight() * 0.4f) {
            logMessage("Mouse near edge, attempting to reset");
            resetMousePosition();
        }
    }

    /** The event for keyboard/ gamepad button inputs */
    public boolean processKeyEvent(KeyEvent event) {
        //Filtering useless events by order of probability
        int eventKeycode = event.getKeyCode();
        if(eventKeycode == KeyEvent.KEYCODE_UNKNOWN) return true;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_UP) return false;
        if(event.getRepeatCount() != 0) return true;
        int action = event.getAction();
        if(action == KeyEvent.ACTION_MULTIPLE) return true;
        // Ignore the cancelled up events. They occur when the user switches layouts.
        // In accordance with https://developer.android.com/reference/android/view/KeyEvent#FLAG_CANCELED
        if(action == KeyEvent.ACTION_UP &&
                (event.getFlags() & KeyEvent.FLAG_CANCELED) != 0) return true;

        //Sometimes, key events comes from SOME keys of the software keyboard
        //Even weirder, is is unknown why a key or another is selected to trigger a keyEvent
        if((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) == KeyEvent.FLAG_SOFT_KEYBOARD){
            if(eventKeycode == KeyEvent.KEYCODE_ENTER) return true; //We already listen to it.
            touchCharInput.dispatchKeyEvent(event);
            return true;
        }

        //Sometimes, key events may come from the mouse
        if(event.getDevice() != null
                && ( (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                ||   (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)  ){

            if(eventKeycode == KeyEvent.KEYCODE_BACK){
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }

        if(Gamepad.isGamepadEvent(event)){
            if(mGamepadHandler == null) createGamepad(this, event.getDevice());

            mInputManager.handleKeyEventInput(getContext(), event, mGamepadHandler);
            return true;
        }

        int index = EfficientAndroidLWJGLKeycode.getIndexByKey(eventKeycode);
        if(EfficientAndroidLWJGLKeycode.containsIndex(index)) {
            EfficientAndroidLWJGLKeycode.execKey(event, index);
            return true;
        }

        // Some events will be generated an infinite number of times when no consumed
        return (event.getFlags() & KeyEvent.FLAG_FALLBACK) == KeyEvent.FLAG_FALLBACK;
    }

    /** Convert the mouse button, then send it
     * @return Whether the event was processed
     */
    public static boolean sendMouseButtonUnconverted(int button, boolean status) {
        int glfwButton = -256;
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                break;
            case MotionEvent.BUTTON_TERTIARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE;
                break;
            case MotionEvent.BUTTON_SECONDARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
                break;
        }
        if(glfwButton == -256) return false;
        sendMouseButton(glfwButton, status);
        return true;
    }

    /** Called when the size need to be set at any point during the surface lifecycle **/
    public void refreshSize(){
        refreshSize(false);
    }

    /** Same as refreshSize, but allows you to force an immediate size update **/
    public void refreshSize(boolean immediate) {
        logMessage("refreshSize called, immediate: " + immediate);
        if(isInLayout() && !immediate) {
            post(this::refreshSize);
            return;
        }
        // Use the width and height of the View instead of display dimensions to avoid
        // getting squiched/stretched due to inconsistencies between the layout and
        // screen dimensions.
        int newWidth = Tools.getDisplayFriendlyRes(getWidth(), LauncherPreferences.PREF_SCALE_FACTOR);
        int newHeight = Tools.getDisplayFriendlyRes(getHeight(), LauncherPreferences.PREF_SCALE_FACTOR);
        if (newHeight < 1 || newWidth < 1) {
            Log.e("MGLSurface", String.format("Impossible resolution : %dx%d", newWidth, newHeight));
            return;
        }
        windowWidth = newWidth;
        windowHeight = newHeight;
        if(mSurface == null){
            Log.w("MGLSurface", "Attempt to refresh size on null surface");
            return;
        }
        if(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE){
            SurfaceView view = (SurfaceView) mSurface;
            if(view.getHolder() != null){
                view.getHolder().setFixedSize(windowWidth, windowHeight);
            }
        }else{
            TextureView view = (TextureView)mSurface;
            if(view.getSurfaceTexture() != null){
                view.getSurfaceTexture().setDefaultBufferSize(windowWidth, windowHeight);
            }
        }

        CallbackBridge.sendUpdateWindowSize(windowWidth, windowHeight);
        
        // 更新中心位置
        mCenterX = getWidth() / 2f;
        mCenterY = getHeight() / 2f;
        logMessage("Window size updated: " + windowWidth + "x" + windowHeight + 
                  ", Center: (" + mCenterX + ", " + mCenterY + ")");
    }

    private void realStart(Surface surface){
        logMessage("realStart called");
        // Initial size set. Request immedate refresh, otherwise the initial width and height for the game
        // may be broken/unknown.
        refreshSize(true);

        //Load Minecraft options:
        MCOptionUtils.set("fullscreen", "off");
        MCOptionUtils.set("overrideWidth", String.valueOf(windowWidth));
        MCOptionUtils.set("overrideHeight", String.valueOf(windowHeight));
        MCOptionUtils.save();
        getMcScale();

        JREUtils.setupBridgeWindow(surface);

        new Thread(() -> {
            try {
                // Wait until the listener is attached
                synchronized(mSurfaceReadyListenerLock) {
                    if(mSurfaceReadyListener == null) mSurfaceReadyListenerLock.wait();
                }

                mSurfaceReadyListener.isReady();
            } catch (Throwable e) {
                Tools.showError(getContext(), e, true);
            }
        }, "JVM Main thread").start();
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        logMessage("onGrabState called: " + isGrabbing);
        post(() -> updateGrabState(isGrabbing));
    }

    private TouchEventProcessor pickEventProcessor(boolean isGrabbing) {
        return isGrabbing ? mIngameProcessor : mInGUIProcessor;
    }

    private void updateGrabState(boolean isGrabbing) {
        logMessage("updateGrabState: " + isGrabbing + ", lastGrabState: " + mLastGrabState);
        if(mLastGrabState != isGrabbing) {
            mCurrentTouchProcessor.cancelPendingActions();
            mCurrentTouchProcessor = pickEventProcessor(isGrabbing);
            mLastGrabState = isGrabbing;
            
            // 更新鼠标状态
            mIsFirstMouseMove = true;
            
            // 更新光标可见性
            updateMouseCursorVisibility(isGrabbing);
            
            // 进入沉浸模式
            if (isGrabbing) {
                enterImmersiveMode();
            } else {
                exitImmersiveMode();
            }
        }
    }

    @Override
    public void onDirectGamepadEnabled() {
        logMessage("onDirectGamepadEnabled called");
        post(()->{
            if(mGamepadHandler != null && mGamepadHandler instanceof Gamepad) {
                ((Gamepad)mGamepadHandler).removeSelf();
            }
            // Force gamepad recreation on next event
            mGamepadHandler = null;
        });
    }

    /** A small interface called when the listener is ready for the first time */
    public interface SurfaceReadyListener {
        void isReady();
    }

    public void setSurfaceReadyListener(SurfaceReadyListener listener){
        synchronized (mSurfaceReadyListenerLock) {
            mSurfaceReadyListener = listener;
            mSurfaceReadyListenerLock.notifyAll();
        }
    }
    
    /* ================ 新增的鼠标控制方法 ================ */
    
    /** 初始化鼠标控制 */
    private void initMouseControl() {
        // 设置能接收鼠标事件
        setFocusable(true);
        setFocusableInTouchMode(true);
        
        // 初始化透明光标（Android 7+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Bitmap transparentBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);
                transparentBitmap.eraseColor(Color.TRANSPARENT);
                mTransparentPointerIcon = PointerIcon.create(transparentBitmap, 0, 0);
                logMessage("Transparent cursor created");
            } catch (Exception e) {
                logMessage("Failed to create transparent cursor: " + e.getMessage());
            }
        }
        
        // 初始化中心位置
        post(() -> {
            mCenterX = getWidth() / 2f;
            mCenterY = getHeight() / 2f;
            logMessage("Initial center position: (" + mCenterX + ", " + mCenterY + ")");
        });
    }
    
    /** 更新鼠标光标可见性 */
    private void updateMouseCursorVisibility(boolean hideCursor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            post(() -> {
                try {
                    if (hideCursor && mTransparentPointerIcon != null) {
                        // 隐藏光标
                        setPointerIcon(mTransparentPointerIcon);
                        mIsCursorHidden = true;
                        logMessage("Cursor hidden (transparent)");
                    } else {
                        // 显示默认光标
                        setPointerIcon(PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_ARROW));
                        mIsCursorHidden = false;
                        logMessage("Cursor shown (default arrow)");
                    }
                } catch (Exception e) {
                    logMessage("Failed to update cursor visibility: " + e.getMessage());
                }
            });
        } else {
            logMessage("Cannot update cursor visibility (Android version < N)");
        }
    }
    
    /** 重置鼠标位置到中心 */
    private void resetMousePosition() {
        logMessage("resetMousePosition called");
        
        // 在Android中无法直接设置鼠标位置
        // 但我们可以重置内部记录的位置，让下一次移动从中心开始计算
        mLastMouseX = mCenterX;
        mLastMouseY = mCenterY;
        mIsFirstMouseMove = true;
        
        logMessage("Reset internal position to center: (" + mCenterX + ", " + mCenterY + ")");
    }
    
    /** 进入沉浸模式 */
    private void enterImmersiveMode() {
        logMessage("Entering immersive mode");
        setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }
    
    /** 退出沉浸模式 */
    private void exitImmersiveMode() {
        logMessage("Exiting immersive mode");
        setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }
    
    /* ================ 日志记录方法 ================ */
    
    /** 初始化日志文件 */
    private void initLogFile() {
        try {
            File logFile = new File(LOG_FILE_PATH);
            if (logFile.exists()) {
                // 备份旧日志
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                File backupFile = new File(LOG_FILE_PATH + ".backup_" + timestamp);
                logFile.renameTo(backupFile);
            }
            
            FileOutputStream fos = new FileOutputStream(logFile);
            OutputStreamWriter writer = new OutputStreamWriter(fos);
            writer.write("=== MinecraftGLSurface Mouse Log ===\n");
            writer.write("Start time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n");
            writer.write("Android SDK: " + Build.VERSION.SDK_INT + "\n");
            writer.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
            writer.write("=====================================\n\n");
            writer.close();
            fos.close();
            
            logMessage("Log file initialized at: " + LOG_FILE_PATH);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to initialize log file", e);
        }
    }
    
    /** 记录消息到日志文件和Logcat */
    private void logMessage(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String logMessage = "[" + timestamp + "] " + message;
        
        Log.d(LOG_TAG, message);
        writeToLogFile(logMessage);
    }
    
    /** 将消息写入日志文件 */
    private synchronized void writeToLogFile(String message) {
        try {
            FileOutputStream fos = new FileOutputStream(LOG_FILE_PATH, true);
            OutputStreamWriter writer = new OutputStreamWriter(fos);
            writer.write(message + "\n");
            writer.close();
            fos.close();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to write to log file", e);
        }
    }
}
