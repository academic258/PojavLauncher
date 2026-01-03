package org.lwjgl.glfw;

import net.kdt.pojavlaunch.*;
import net.kdt.pojavlaunch.customcontrols.gamepad.direct.DirectGamepadEnableHandler;

import android.content.*;
import android.util.Log;
import android.view.Choreographer;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import dalvik.annotation.optimization.CriticalNative;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CallbackBridge {
    public static final Choreographer sChoreographer = Choreographer.getInstance();
    private static boolean isGrabbing = false;
    private static final ArrayList<GrabListener> grabListeners = new ArrayList<>();
    // Use a weak reference here to avoid possibly statically referencing a Context.
    private static @Nullable WeakReference<DirectGamepadEnableHandler> sDirectGamepadEnableHandler;
    
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;
    
    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static float mouseX, mouseY;
    public volatile static boolean holdingAlt, holdingCapslock, holdingCtrl,
            holdingNumlock, holdingShift;

    public static final ByteBuffer sGamepadButtonBuffer;
    public static final FloatBuffer sGamepadAxisBuffer;
    public static boolean sGamepadDirectInput = false;

    /* 新增：鼠标相对模式支持 */
    private static boolean sIsRelativeMode = false;
    private static float sRelativeMouseX = 0, sRelativeMouseY = 0;
    private static float sLastMouseX = 0, sLastMouseY = 0;
    private static final String MOUSE_LOG_TAG = "CallbackBridge";
    private static final String LOG_FILE_PATH = "/sdcard/Download/callbackbridge_mouse_log.txt";

    public static void putMouseEventWithCoords(int button, float x, float y) {
        putMouseEventWithCoords(button, true, x, y);
        sChoreographer.postFrameCallbackDelayed(l -> putMouseEventWithCoords(button, false, x, y), 33);
    }
    
    public static void putMouseEventWithCoords(int button, boolean isDown, float x, float y /* , int dz, long nanos */) {
        sendCursorPos(x, y);
        sendMouseKeycode(button, CallbackBridge.getCurrentMods(), isDown);
    }

    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        
        // 记录原始位置
        logMouseEvent("Raw sendCursorPos: (" + x + ", " + y + "), isGrabbing: " + isGrabbing + 
                     ", isRelativeMode: " + sIsRelativeMode);
        
        if (sIsRelativeMode && isGrabbing) {
            // 相对模式下，我们使用累计的相对移动
            sRelativeMouseX += x;
            sRelativeMouseY += y;
            
            // 限制在合理范围内
            sRelativeMouseX = Math.max(0, Math.min(sRelativeMouseX, windowWidth));
            sRelativeMouseY = Math.max(0, Math.min(sRelativeMouseY, windowHeight));
            
            logMouseEvent("Relative mode - accumulated: (" + sRelativeMouseX + ", " + sRelativeMouseY + ")");
            
            // 使用相对累计位置
            nativeSendCursorPos(sRelativeMouseX, sRelativeMouseY);
        } else {
            // 绝对模式，直接发送
            nativeSendCursorPos(mouseX, mouseY);
        }
    }

    public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        // TODO CHECK: This may cause input issue, not receive input!
        if(keycode != 0)  nativeSendKey(keycode,scancode,isDown ? 1 : 0, modifiers);
        if(isDown && keychar != '\u0000') {
            nativeSendCharMods(keychar,modifiers);
            nativeSendChar(keychar);
        }
    }

    public static void sendChar(char keychar, int modifiers){
        nativeSendCharMods(keychar,modifiers);
        nativeSendChar(keychar);
    }

    public static void sendKeyPress(int keyCode, int modifiers, boolean status) {
        sendKeyPress(keyCode, 0, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, int scancode, int modifiers, boolean status) {
        sendKeyPress(keyCode, '\u0000', scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, char keyChar, int scancode, int modifiers, boolean status) {
        CallbackBridge.sendKeycode(keyCode, keyChar, scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode) {
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
    }

    public static void sendMouseButton(int button, boolean status) {
        CallbackBridge.sendMouseKeycode(button, CallbackBridge.getCurrentMods(), status);
    }

    public static void sendMouseKeycode(int button, int modifiers, boolean isDown) {
        nativeSendMouseButton(button, isDown ? 1 : 0, modifiers);
    }

    public static void sendMouseKeycode(int keycode) {
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), true);
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), false);
    }
    
    public static void sendScroll(double xoffset, double yoffset) {
        nativeSendScroll(xoffset, yoffset);
    }

    public static void sendUpdateWindowSize(int w, int h) {
        nativeSendScreenSize(w, h);
    }

    public static boolean isGrabbing() {
        return isGrabbing;
    }

    // 新增：设置相对模式
    public static void setRelativeMouseMode(boolean relative) {
        sIsRelativeMode = relative;
        logMouseEvent("setRelativeMouseMode: " + relative);
        
        if (relative) {
            // 重置相对位置
            sRelativeMouseX = windowWidth / 2f;
            sRelativeMouseY = windowHeight / 2f;
            sLastMouseX = 0;
            sLastMouseY = 0;
        }
    }
    
    // 新增：处理相对移动
    public static void sendRelativeMouseMovement(float deltaX, float deltaY) {
        if (sIsRelativeMode && isGrabbing) {
            // 累加相对移动
            sRelativeMouseX += deltaX;
            sRelativeMouseY += deltaY;
            
            // 限制范围
            sRelativeMouseX = Math.max(0, Math.min(sRelativeMouseX, windowWidth));
            sRelativeMouseY = Math.max(0, Math.min(sRelativeMouseY, windowHeight));
            
            logMouseEvent("Relative movement - delta: (" + deltaX + ", " + deltaY + 
                         "), accumulated: (" + sRelativeMouseX + ", " + sRelativeMouseY + ")");
            
            // 发送累计位置
            mouseX = sRelativeMouseX;
            mouseY = sRelativeMouseY;
            nativeSendCursorPos(sRelativeMouseX, sRelativeMouseY);
        }
    }

    // Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    public static @Nullable String accessAndroidClipboard(int type, String copy) {
        switch (type) {
            case CLIPBOARD_COPY:
                MainActivity.GLOBAL_CLIPBOARD.setPrimaryClip(ClipData.newPlainText("Copy", copy));
                return null;

            case CLIPBOARD_PASTE:
                if (MainActivity.GLOBAL_CLIPBOARD.hasPrimaryClip() && MainActivity.GLOBAL_CLIPBOARD.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    return MainActivity.GLOBAL_CLIPBOARD.getPrimaryClip().getItemAt(0).getText().toString();
                } else {
                    return "";
                }

            case CLIPBOARD_OPEN:
                MainActivity.openLink(copy);
                return null;
            default: return null;
        }
    }

    public static int getCurrentMods() {
        int currMods = 0;
        if (holdingAlt) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_ALT;
        } if (holdingCapslock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK;
        } if (holdingCtrl) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CONTROL;
        } if (holdingNumlock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK;
        } if (holdingShift) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_SHIFT;
        }
        return currMods;
    }

    public static void setModifiers(int keyCode, boolean isDown){
        switch (keyCode){
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT:
                CallbackBridge.holdingShift = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL:
                CallbackBridge.holdingCtrl = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT:
                CallbackBridge.holdingAlt = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK:
                CallbackBridge.holdingCapslock = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK:
                CallbackBridge.holdingNumlock = isDown;
        }
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    private static void onDirectInputEnable() {
        Log.i("CallbackBridge", "onDirectInputEnable()");
        DirectGamepadEnableHandler enableHandler = Tools.getWeakReference(sDirectGamepadEnableHandler);
        if(enableHandler != null) enableHandler.onDirectGamepadEnabled();
        sGamepadDirectInput = true;
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    private static void onGrabStateChanged(final boolean grabbing) {
        boolean oldGrabbing = isGrabbing;
        isGrabbing = grabbing;
        
        // 抓取状态变化时设置相对模式
        if (grabbing != oldGrabbing) {
            setRelativeMouseMode(grabbing);
        }
        
        sChoreographer.postFrameCallbackDelayed((time) -> {
            // If the grab re-changed, skip notify process
            if(isGrabbing != grabbing) return;

            System.out.println("Grab changed : " + grabbing);
            synchronized (grabListeners) {
                for (GrabListener g : grabListeners) g.onGrabState(grabbing);
            }

        }, 16);

    }
    
    public static void addGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            listener.onGrabState(isGrabbing);
            grabListeners.add(listener);
        }
    }
    
    public static void removeGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            grabListeners.remove(listener);
        }
    }

    public static FloatBuffer createGamepadAxisBuffer() {
        ByteBuffer axisByteBuffer = nativeCreateGamepadAxisBuffer();
        // NOTE: hardcoded order (also in jre_lwjgl3glfw CallbackBridge)
        return axisByteBuffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    }

    public static void setDirectGamepadEnableHandler(DirectGamepadEnableHandler h) {
        sDirectGamepadEnableHandler = new WeakReference<>(h);
    }

    /* ================ 日志记录方法 ================ */
    
    private static void initLogFile() {
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
            writer.write("=== CallbackBridge Mouse Log ===\n");
            writer.write("Start time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n");
            writer.write("Android SDK: " + android.os.Build.VERSION.SDK_INT + "\n");
            writer.write("================================\n\n");
            writer.close();
            fos.close();
        } catch (Exception e) {
            Log.e(MOUSE_LOG_TAG, "Failed to init log file", e);
        }
    }
    
    private static void logMouseEvent(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String logMessage = "[" + timestamp + "] " + message;
        
        Log.d(MOUSE_LOG_TAG, message);
        writeToLogFile(logMessage);
    }
    
    private static synchronized void writeToLogFile(String message) {
        try {
            FileOutputStream fos = new FileOutputStream(LOG_FILE_PATH, true);
            OutputStreamWriter writer = new OutputStreamWriter(fos);
            writer.write(message + "\n");
            writer.close();
            fos.close();
        } catch (Exception e) {
            Log.e(MOUSE_LOG_TAG, "Failed to write log", e);
        }
    }

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    // GLFW: GLFWCharModsCallback deprecated, but is Minecraft still use?
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    // private static native void nativeSendCursorEnter(int entered);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);
    public static native void nativeSetWindowAttrib(int attrib, int value);
    private static native ByteBuffer nativeCreateGamepadButtonBuffer();
    private static native ByteBuffer nativeCreateGamepadAxisBuffer();
    
    static {
        System.loadLibrary("pojavexec");
        sGamepadButtonBuffer = nativeCreateGamepadButtonBuffer();
        sGamepadAxisBuffer = createGamepadAxisBuffer();
        
        // 初始化日志文件
        initLogFile();
        logMouseEvent("CallbackBridge loaded");
    }
}
