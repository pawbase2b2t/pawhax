package com.pawhax.util;

import com.pawhax.PawHax;
import com.sun.jna.*;
import com.sun.jna.ptr.*;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public class SmtcProvider {

    private static final String CLASS_NAME =
        "Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager";

    // {00000035-0000-0000-C000-000000000046}
    private static final byte[] IID_ACTIVATION_FACTORY = {
        0x35, 0x00, 0x00, 0x00,
        0x00, 0x00,
        0x00, 0x00,
        (byte)0xC0, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x46
    };

    // {AF86E2E0-B12D-4C6A-9C5A-D7AA65101E90}
    private static final byte[] IID_INSPECTABLE = {
        (byte)0xE0, (byte)0xE2, (byte)0x86, (byte)0xAF,
        (byte)0x2D, (byte)0xB1,
        (byte)0x6A, (byte)0x4C,
        (byte)0x9C, (byte)0x5A,
        (byte)0xD7, (byte)0xAA, (byte)0x65, (byte)0x10, (byte)0x1E, (byte)0x90
    };

    private interface Combase extends Library {
        Combase INSTANCE = Native.load("combase", Combase.class);
        int     RoInitialize(int initType);
        void    RoUninitialize();
        int     RoGetActivationFactory(Pointer classId, Pointer riid, PointerByReference factory);
        int     WindowsCreateString(WString src, int length, PointerByReference out);
        void    WindowsDeleteString(Pointer hstring);
        Pointer WindowsGetStringRawBuffer(Pointer hstring, IntByReference length);
    }

    private interface Ole32 extends Library {
        Ole32 INSTANCE = Native.load("ole32", Ole32.class);
        void CoTaskMemFree(Pointer ptr);
    }

    private static final AtomicReference<String[]> currentTrack = new AtomicReference<>(null);
    private static volatile boolean running     = false;
    private static          Thread  pollThread  = null;
    private static          Pointer smtcManager = null;

    public static String[] getTrack()  { return currentTrack.get(); }
    public static String   getTitle()  { String[] t = currentTrack.get(); return t != null ? t[0] : null; }
    public static String   getArtist() { String[] t = currentTrack.get(); return t != null ? t[1] : null; }

    public static synchronized void start() {
        if (running) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            PawHax.LOG.warn("[NowPlaying] SmtcProvider only works on Windows (os={}); not starting.", os);
            return;
        }
        running = true;
        pollThread = new Thread(SmtcProvider::run, "pawhax-smtc");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    public static synchronized void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        currentTrack.set(null);
        releaseManager();
    }

    private static void run() {
        try {
            Combase.INSTANCE.RoInitialize(1);
            smtcManager = acquireManager();
            if (smtcManager == null) return;
            while (running) {
                try {
                    poll();
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    PawHax.LOG.warn("[NowPlaying] poll error: {}", e.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            releaseManager();
            try { Combase.INSTANCE.RoUninitialize(); } catch (Exception ignored) {}
        }
    }

    private static Pointer acquireManager() throws InterruptedException {
        WString classNameW = new WString(CLASS_NAME);
        PointerByReference classHstr = new PointerByReference();
        int hr = Combase.INSTANCE.WindowsCreateString(classNameW, CLASS_NAME.length(), classHstr);
        if (hr != 0) return null;

        Memory iidActFac = new Memory(16);
        iidActFac.write(0, IID_ACTIVATION_FACTORY, 0, 16);
        PointerByReference factoryRef = new PointerByReference();
        hr = Combase.INSTANCE.RoGetActivationFactory(classHstr.getValue(), iidActFac, factoryRef);
        Combase.INSTANCE.WindowsDeleteString(classHstr.getValue());
        if (hr != 0 || factoryRef.getValue() == null) return null;

        Pointer factory = factoryRef.getValue();
        try {
            // GetIids (vtable[3]) -- ask the factory what WinRT interfaces it supports
            IntByReference countRef = new IntByReference();
            PointerByReference iidsRef = new PointerByReference();
            hr = vtableCall(factory, 3, countRef, iidsRef);

            byte[] staticsIid = null;
            if (hr == 0 && iidsRef.getValue() != null) {
                int count = countRef.getValue();
                Pointer iidsPtr = iidsRef.getValue();
                for (int i = 0; i < count; i++) {
                    byte[] iid = iidsPtr.getByteArray((long) i * 16, 16);
                    // skip known infrastructure IIDs; the remainder is the statics interface
                    if (!Arrays.equals(iid, IID_ACTIVATION_FACTORY) && !Arrays.equals(iid, IID_INSPECTABLE)) {
                        staticsIid = iid;
                    }
                }
                Ole32.INSTANCE.CoTaskMemFree(iidsPtr);
            }

            if (staticsIid == null) return null;

            // QueryInterface (vtable[0]) to the statics interface
            Memory staticsIidMem = new Memory(16);
            staticsIidMem.write(0, staticsIid, 0, 16);
            PointerByReference staticsRef = new PointerByReference();
            hr = vtableCall(factory, 0, staticsIidMem, staticsRef);
            if (hr != 0 || staticsRef.getValue() == null) return null;

            Pointer statics = staticsRef.getValue();
            try {
                // RequestAsync at vtable[6] of the statics interface
                PointerByReference asyncRef = new PointerByReference();
                hr = vtableCall(statics, 6, asyncRef);
                if (hr != 0 || asyncRef.getValue() == null) return null;
                return waitForResult(asyncRef.getValue());
            } finally {
                release(statics);
            }
        } finally {
            release(factory);
        }
    }

    private static void poll() throws InterruptedException {
        // vtable[6] = GetCurrentSession
        PointerByReference sessionRef = new PointerByReference();
        int hr = vtableCall(smtcManager, 6, sessionRef);
        if (hr != 0 || sessionRef.getValue() == null) {
            currentTrack.set(null);
            return;
        }
        Pointer session = sessionRef.getValue();
        PointerByReference asyncRef = new PointerByReference();
        try {
            // vtable[7] = TryGetMediaPropertiesAsync
            hr = vtableCall(session, 7, asyncRef);
        } finally {
            release(session);
        }
        if (hr != 0 || asyncRef.getValue() == null) {
            currentTrack.set(null);
            return;
        }

        Pointer props = waitForResult(asyncRef.getValue());
        if (props == null) {
            currentTrack.set(null);
            return;
        }
        try {
            String title  = readHstring(props, 6); // get_Title
            String artist = readHstring(props, 8); // get_Artist
            currentTrack.set((title != null || artist != null) ? new String[]{title, artist} : null);
        } finally {
            release(props);
        }
    }

    /*
     * IAsyncOperation<T> vtable (inherits from IInspectable, not IAsyncInfo):
     *   [6] put_Completed
     *   [7] get_Completed
     *   [8] GetResults  -- returns E_ILLEGAL_METHOD_CALL (0x8000000E) while still pending
     *
     * We poll GetResults directly rather than going through IAsyncInfo::get_Status
     * because IAsyncInfo is a separate interface that requires a QI, not part of this vtable.
     */
    private static Pointer waitForResult(Pointer asyncOp) throws InterruptedException {
        // nativeValue < 0x10000 catches invalid low-page pointers (Windows reserves 0x0-0xFFFF)
        if (asyncOp == null || Pointer.nativeValue(asyncOp) < 0x10000L) return null;
        long deadline = System.currentTimeMillis() + 5000;
        try {
            while (System.currentTimeMillis() < deadline) {
                PointerByReference resultRef = new PointerByReference();
                int hr = vtableCall(asyncOp, 8, resultRef);
                if (hr == 0) return resultRef.getValue();
                if (hr != 0x8000000E) return null; // anything other than "not yet complete" is fatal
                Thread.sleep(10);
            }
            return null;
        } finally {
            release(asyncOp);
        }
    }

    private static String readHstring(Pointer comObj, int slot) {
        PointerByReference hstrRef = new PointerByReference();
        int hr = vtableCall(comObj, slot, hstrRef);
        if (hr != 0 || hstrRef.getValue() == null) return null;
        Pointer hstr = hstrRef.getValue();
        try {
            IntByReference len = new IntByReference();
            Pointer raw = Combase.INSTANCE.WindowsGetStringRawBuffer(hstr, len);
            if (raw == null || len.getValue() == 0) return null;
            String s = raw.getWideString(0);
            return s.isEmpty() ? null : s;
        } finally {
            Combase.INSTANCE.WindowsDeleteString(hstr);
        }
    }

    private static int vtableCall(Pointer comObj, int slot, Object... args) {
        Pointer vtable  = comObj.getPointer(0);
        Pointer funcPtr = vtable.getPointer((long) slot * Native.POINTER_SIZE);
        Function func   = Function.getFunction(funcPtr);
        Object[] full   = new Object[args.length + 1];
        full[0] = comObj;
        System.arraycopy(args, 0, full, 1, args.length);
        return func.invokeInt(full);
    }

    private static void release(Pointer comObj) {
        if (comObj != null) vtableCall(comObj, 2);
    }

    private static void releaseManager() {
        if (smtcManager != null) {
            release(smtcManager);
            smtcManager = null;
        }
    }
}
