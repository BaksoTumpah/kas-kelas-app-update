package com.kaskelas.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(KasLockPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
