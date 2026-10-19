package com.kaskelas.app;

import android.app.Activity;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Kunci aplikasi Kas Kelas:
 *  - sidik jari / wajah bawaan HP (BiometricPrompt), tanpa mendaftarkan data apa pun di aplikasi
 *  - membaca & menulis NFC tag (teks NDEF) untuk membuka kunci memakai token
 */
@CapacitorPlugin(name = "KasLock")
public class KasLockPlugin extends Plugin implements NfcAdapter.ReaderCallback {

    private BiometricPrompt prompt = null;
    private boolean nfcOn = false;
    private String writeText = null;
    private PluginCall writeCall = null;

    /* ---------------- status ---------------- */
    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        String bio = "unavailable";
        try {
            int can = BiometricManager.from(getContext()).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
            if (can == BiometricManager.BIOMETRIC_SUCCESS) bio = "ok";
            else if (can == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) bio = "none_enrolled";
        } catch (Exception e) { /* tetap unavailable */ }
        r.put("bio", bio);
        String nfc = "none";
        try {
            NfcAdapter a = NfcAdapter.getDefaultAdapter(getContext());
            if (a != null) nfc = a.isEnabled() ? "ok" : "off";
        } catch (Exception e) { /* tetap none */ }
        r.put("nfc", nfc);
        call.resolve(r);
    }

    /* ---------------- sidik jari / wajah ---------------- */
    @PluginMethod
    public void bioPrompt(final PluginCall call) {
        final Activity act = getActivity();
        if (!(act instanceof FragmentActivity)) { call.reject("no_activity"); return; }
        final String title = call.getString("title", "Buka Kas Kelas");
        final String subtitle = call.getString("subtitle", "");
        final String cancel = call.getString("cancel", "Pakai token");
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    BiometricPrompt.PromptInfo.Builder b = new BiometricPrompt.PromptInfo.Builder()
                            .setTitle(title)
                            .setNegativeButtonText(cancel)
                            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK);
                    if (subtitle != null && subtitle.length() > 0) b.setSubtitle(subtitle);
                    BiometricPrompt p = new BiometricPrompt((FragmentActivity) act, ContextCompat.getMainExecutor(act),
                            new BiometricPrompt.AuthenticationCallback() {
                                @Override
                                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                    prompt = null;
                                    JSObject r = new JSObject();
                                    r.put("ok", true);
                                    call.resolve(r);
                                }

                                @Override
                                public void onAuthenticationError(int errorCode, CharSequence errString) {
                                    prompt = null;
                                    JSObject r = new JSObject();
                                    r.put("ok", false);
                                    r.put("code", errorCode);
                                    r.put("message", String.valueOf(errString));
                                    call.resolve(r);
                                }
                            });
                    prompt = p;
                    p.authenticate(b.build());
                } catch (Exception e) {
                    prompt = null;
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    @PluginMethod
    public void bioCancel(final PluginCall call) {
        Activity act = getActivity();
        if (act == null) { call.resolve(); return; }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try { if (prompt != null) prompt.cancelAuthentication(); } catch (Exception e) { /* abaikan */ }
                call.resolve();
            }
        });
    }

    /* ---------------- NFC ---------------- */
    private void enableReader() {
        try {
            final Activity act = getActivity();
            final NfcAdapter a = NfcAdapter.getDefaultAdapter(getContext());
            if (act == null || a == null || !a.isEnabled()) return;
            a.enableReaderMode(act, this,
                    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V,
                    null);
        } catch (Exception e) { /* abaikan */ }
    }

    private void disableReader() {
        try {
            Activity act = getActivity();
            NfcAdapter a = NfcAdapter.getDefaultAdapter(getContext());
            if (act != null && a != null) a.disableReaderMode(act);
        } catch (Exception e) { /* abaikan */ }
    }

    @PluginMethod
    public void nfcStart(final PluginCall call) {
        NfcAdapter a = NfcAdapter.getDefaultAdapter(getContext());
        if (a == null || !a.isEnabled()) { call.reject("nfc_unavailable"); return; }
        nfcOn = true;
        Activity act = getActivity();
        if (act == null) { call.resolve(); return; }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                enableReader();
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void nfcStop(final PluginCall call) {
        nfcOn = false;
        Activity act = getActivity();
        if (act == null) { call.resolve(); return; }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                disableReader();
                call.resolve();
            }
        });
    }

    @Override
    protected void handleOnPause() {
        if (nfcOn) disableReader();
        super.handleOnPause();
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (nfcOn) enableReader();
    }

    @PluginMethod
    public void nfcWrite(PluginCall call) {
        String text = call.getString("text");
        if (text == null || text.length() == 0) { call.reject("empty"); return; }
        if (writeCall != null) {
            JSObject old = new JSObject();
            old.put("ok", false);
            old.put("error", "cancelled");
            writeCall.resolve(old);
        }
        writeText = text;
        writeCall = call;
    }

    @PluginMethod
    public void nfcWriteCancel(PluginCall call) {
        PluginCall c = writeCall;
        writeCall = null;
        writeText = null;
        if (c != null) {
            JSObject r = new JSObject();
            r.put("ok", false);
            r.put("error", "cancelled");
            c.resolve(r);
        }
        call.resolve();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        if (writeText != null && writeCall != null) { doWrite(tag); return; }
        JSObject o = new JSObject();
        String text = "";
        try {
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                NdefMessage m = ndef.getCachedNdefMessage();
                if (m == null) {
                    ndef.connect();
                    m = ndef.getNdefMessage();
                    ndef.close();
                }
                if (m != null) {
                    for (NdefRecord r : m.getRecords()) {
                        String t = textOf(r);
                        if (t != null && t.length() > 0) { text = t; break; }
                    }
                }
            }
        } catch (Exception e) {
            o.put("error", String.valueOf(e.getMessage()));
        }
        o.put("text", text);
        notifyListeners("nfcTag", o);
    }

    private static String textOf(NdefRecord r) {
        try {
            byte[] p = r.getPayload();
            if (r.getTnf() == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(r.getType(), NdefRecord.RTD_TEXT) && p.length > 0) {
                int status = p[0] & 0xFF;
                int lang = status & 0x3F;
                Charset cs = Charset.forName((status & 0x80) == 0 ? "UTF-8" : "UTF-16");
                return new String(p, 1 + lang, p.length - 1 - lang, cs);
            }
            if (r.getTnf() == NdefRecord.TNF_MIME_MEDIA) {
                return new String(p, Charset.forName("UTF-8"));
            }
        } catch (Exception e) { /* abaikan */ }
        return null;
    }

    private void doWrite(Tag tag) {
        String err = null;
        try {
            NdefMessage msg = new NdefMessage(new NdefRecord[]{NdefRecord.createTextRecord("id", writeText)});
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                if (!ndef.isWritable()) err = "read_only";
                else if (ndef.getMaxSize() < msg.toByteArray().length) err = "too_small";
                else ndef.writeNdefMessage(msg);
                ndef.close();
            } else {
                NdefFormatable f = NdefFormatable.get(tag);
                if (f == null) err = "unsupported";
                else {
                    f.connect();
                    f.format(msg);
                    f.close();
                }
            }
        } catch (Exception e) {
            err = "io";
        }
        PluginCall c = writeCall;
        writeCall = null;
        writeText = null;
        if (c != null) {
            JSObject r = new JSObject();
            r.put("ok", err == null);
            if (err != null) r.put("error", err);
            c.resolve(r);
        }
    }
}
