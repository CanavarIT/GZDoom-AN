package com.az.doom;

import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import org.libsdl.app.SDLActivity;

/**
 * Runs the engine (libgzdoom.so, entry point SDL_main) inside SDL's activity and
 * draws the touch controls on top of the SDL surface.
 */
public class GZDoomActivity extends SDLActivity {
    static final String EXTRA_ARGS = "args";
    static final String EXTRA_LOOK_SENS = "look_sens";
    static final String EXTRA_HAPTICS = "haptics";
    static final String EXTRA_CONTROLS = "controls";

    /** Bit 0 set while a menu / the console has captured input. Implemented in android_support.cpp. */
    static native int nativeUiState();

    private TouchControlsView controls;

    @Override
    protected String[] getLibraries() {
        // c++_shared, libopenal.so and libzmusic.so are pulled in as dependencies of libgzdoom.so
        return new String[] { "SDL2", "gzdoom" };
    }

    @Override
    protected String[] getArguments() {
        String[] args = getIntent() != null ? getIntent().getStringArrayExtra(EXTRA_ARGS) : null;
        return args != null ? args : new String[0];
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Some SDL builds ship the HID code in a separate library
        try {
            System.loadLibrary("hidapi");
        } catch (Throwable ignored) {
        }
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent i = getIntent();
        boolean show = i == null || i.getBooleanExtra(EXTRA_CONTROLS, true);
        float sens = i != null ? i.getFloatExtra(EXTRA_LOOK_SENS, 1.0f) : 1.0f;
        boolean haptics = i == null || i.getBooleanExtra(EXTRA_HAPTICS, true);

        if (mLayout != null) {
            controls = new TouchControlsView(this, sens, haptics, show);
            mLayout.addView(controls, new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    @Override
    public void onBackPressed() {
        // Closes the layout editor if it is open
        if (controls != null && controls.onBack()) return;
        // Otherwise the Android back button acts as ESC (opens/closes the menu)
        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // The engine keeps a lot of global state and cannot be restarted inside a live
        // process, so end this (separate ":game") process together with the activity.
        if (isFinishing()) {
            Process.killProcess(Process.myPid());
        }
    }
}
