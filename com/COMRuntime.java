/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom.com;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * FFM bindings to the Windows Runtime C ABI entry points in combase.dll.
 *
 * All WinRT object activation and HSTRING management goes through here.
 * This is the only class that calls into combase.dll — all other COM classes
 * receive function pointers from here.
 *
 * WinRT C ABI functions bound:
 *   RoInitialize(RO_INIT_TYPE initType) → HRESULT
 *   RoUninitialize()
 *   RoActivateInstance(HSTRING classId, IInspectable** instance) → HRESULT
 *   RoGetActivationFactory(HSTRING classId, REFIID iid, void** factory) → HRESULT
 *   WindowsCreateString(PCNZWCH sourceString, UINT32 length, HSTRING* string) → HRESULT
 *   WindowsDeleteString(HSTRING string) → HRESULT
 *   WindowsGetStringRawBuffer(HSTRING string, UINT32* length) → PCWSTR
 *
 * NOTE: This class only runs on Windows. All methods throw UnsupportedOperationException
 *       on Linux/macOS. Platform check is performed in checkWindows().
 */
public class COMRuntime {

    // ── RoInitType ───────────────────────────────────────────────────────────
    public static final int RO_INIT_SINGLE_THREADED = 0;
    public static final int RO_INIT_MULTITHREADED   = 1;

    // ── HRESULT constants ────────────────────────────────────────────────────
    public static final int S_OK          = 0x00000000;
    public static final int S_FALSE       = 0x00000001;
    public static final int E_NOINTERFACE = 0x80004002;
    public static final int E_POINTER     = 0x80004003;
    public static final int E_INVALIDARG  = 0x80070057;
    public static final int CLASS_NOT_REG = 0x80040154;

    // ── Platform check ───────────────────────────────────────────────────────
    private static final boolean IS_WINDOWS =
        System.getProperty( "os.name", "" ).toLowerCase().contains( "win" );

    // ── FFM handles (null on non-Windows) ───────────────────────────────────
    private static final Linker       LINKER;
    private static final SymbolLookup COMBASE;

    private static final MethodHandle RoInitialize;
    private static final MethodHandle RoUninitialize;
    private static final MethodHandle RoActivateInstance;
    private static final MethodHandle RoGetActivationFactory;
    private static final MethodHandle WindowsCreateString;
    private static final MethodHandle WindowsDeleteString;
    private static final MethodHandle WindowsGetStringRawBuffer;

    static {
        if( IS_WINDOWS ) {
            LINKER  = Linker.nativeLinker();
            COMBASE = SymbolLookup.libraryLookup( "combase.dll", Arena.global() );

            RoInitialize = LINKER.downcallHandle(
                COMBASE.find( "RoInitialize" ).orElseThrow(),
                FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.JAVA_INT ) );
            RoUninitialize = LINKER.downcallHandle(
                COMBASE.find( "RoUninitialize" ).orElseThrow(),
                FunctionDescriptor.ofVoid() );
            RoActivateInstance = LINKER.downcallHandle(
                COMBASE.find( "RoActivateInstance" ).orElseThrow(),
                FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS ) );
            RoGetActivationFactory = LINKER.downcallHandle(
                COMBASE.find( "RoGetActivationFactory" ).orElseThrow(),
                FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS ) );
            WindowsCreateString = LINKER.downcallHandle(
                COMBASE.find( "WindowsCreateString" ).orElseThrow(),
                FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) );
            WindowsDeleteString = LINKER.downcallHandle(
                COMBASE.find( "WindowsDeleteString" ).orElseThrow(),
                FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) );
            WindowsGetStringRawBuffer = LINKER.downcallHandle(
                COMBASE.find( "WindowsGetStringRawBuffer" ).orElseThrow(),
                FunctionDescriptor.of( ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS ) );
        } else {
            LINKER                    = null;
            COMBASE                   = null;
            RoInitialize              = null;
            RoUninitialize            = null;
            RoActivateInstance        = null;
            RoGetActivationFactory    = null;
            WindowsCreateString       = null;
            WindowsDeleteString       = null;
            WindowsGetStringRawBuffer = null;
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Initialize the Windows Runtime for the current thread.
     * S_FALSE is acceptable (thread already initialized).
     */
    public static void initialize( final int initType )
    {
        checkWindows();
        try {
            final int hr = (int) RoInitialize.invoke( initType );
            if( hr != S_FALSE ) checkHResult( hr );
        }
        catch( final Throwable t ) { throw wrap( t ); }
    }

    /** Uninitialize WinRT for the current thread. */
    public static void uninitialize()
    {
        checkWindows();
        try { RoUninitialize.invoke(); }
        catch( final Throwable t ) { throw wrap( t ); }
    }

    /**
     * Activate a WinRT class and return its IInspectable* pointer.
     *
     * @param runtimeClassName e.g. "Windows.Devices.Bluetooth.BluetoothLEDevice"
     * @param arena            for the HSTRING and output pointer allocation
     */
    public static MemorySegment activateInstance( final String runtimeClassName, final Arena arena )
    {
        checkWindows();
        try {
            final MemorySegment hs = createHString( runtimeClassName, arena );
            final MemorySegment pp = arena.allocate( ValueLayout.ADDRESS );
            final int           hr = (int) RoActivateInstance.invoke( hs, pp );
            deleteHString( hs );
            checkHResult( hr );
            return pp.get( ValueLayout.ADDRESS, 0 );
        }
        catch( final Throwable t ) { throw wrap( t ); }
    }

    /**
     * Get an activation factory for a WinRT class (static methods / factory activation).
     *
     * @param runtimeClassName e.g. "Windows.Devices.Bluetooth.BluetoothLEDevice"
     * @param iidBytes         16-byte IID of the factory interface
     * @param arena            allocation arena
     */
    public static MemorySegment getActivationFactory( final String runtimeClassName,
        final byte[] iidBytes, final Arena arena )
    {
        checkWindows();
        try {
            final MemorySegment hs  = createHString( runtimeClassName, arena );
            final MemorySegment iid = arena.allocate( 16 );
            MemorySegment.copy( iidBytes, 0, iid, ValueLayout.JAVA_BYTE, 0, 16 );
            final MemorySegment pp  = arena.allocate( ValueLayout.ADDRESS );
            final int           hr  = (int) RoGetActivationFactory.invoke( hs, iid, pp );
            deleteHString( hs );
            checkHResult( hr );
            return pp.get( ValueLayout.ADDRESS, 0 );
        }
        catch( final Throwable t ) { throw wrap( t ); }
    }

    /**
     * Create an HSTRING from a Java String.
     * The returned segment is an opaque handle — caller must deleteHString() it.
     */
    public static MemorySegment createHString( final String value, final Arena arena )
    {
        checkWindows();
        try {
            final byte[]        utf16 = value.getBytes( StandardCharsets.UTF_16LE );
            final MemorySegment buf   = arena.allocate( utf16.length + 2 );
            MemorySegment.copy( utf16, 0, buf, ValueLayout.JAVA_BYTE, 0, utf16.length );
            final MemorySegment ph    = arena.allocate( ValueLayout.ADDRESS );
            final int           hr    = (int) WindowsCreateString.invoke( buf, value.length(), ph );
            checkHResult( hr );
            return ph.get( ValueLayout.ADDRESS, 0 );
        }
        catch( final Throwable t ) { throw wrap( t ); }
    }

    /** Delete an HSTRING created by createHString(). */
    public static void deleteHString( final MemorySegment hstring )
    {
        checkWindows();
        try { WindowsDeleteString.invoke( hstring ); }
        catch( final Throwable t ) { throw wrap( t ); }
    }

    /** Convert an HSTRING to a Java String. */
    public static String hstringToString( final MemorySegment hstring, final Arena arena )
    {
        checkWindows();
        try {
            final MemorySegment pLen  = arena.allocate( ValueLayout.JAVA_INT );
            final MemorySegment wptr  = (MemorySegment) WindowsGetStringRawBuffer.invoke( hstring, pLen );
            final int           len   = pLen.get( ValueLayout.JAVA_INT, 0 );
            final byte[]        utf16 = wptr.reinterpret( len * 2L ).toArray( ValueLayout.JAVA_BYTE );
            return new String( utf16, StandardCharsets.UTF_16LE );
        }
        catch( final Throwable t ) { throw wrap( t ); }
    }

    /** Throw WinRTException if hresult has the failure bit (bit 31) set. */
    public static void checkHResult( final int hresult )
    {
        if( (hresult & 0x80000000) != 0 ) throw new jcom.winrt.WinRTException( hresult );
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static void checkWindows()
    {
        if( !IS_WINDOWS )
            throw new UnsupportedOperationException(
                "COMRuntime is only available on Windows. Current OS: "
                + System.getProperty( "os.name" ) );
    }

    private static RuntimeException wrap( final Throwable t )
    {
        if( t instanceof RuntimeException re ) return re;
        return new RuntimeException( t );
    }
}
