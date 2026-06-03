/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom.com;

import java.lang.foreign.*;
import java.util.UUID;

/**
 * Wrapper for IInspectable — the base interface of all WinRT objects.
 *
 * Extends COMObject (IUnknown) with 3 additional vtable slots:
 *   slot 3: GetIids(ULONG* iidCount, IID** iids) → HRESULT
 *   slot 4: GetRuntimeClassName(HSTRING* className) → HRESULT
 *   slot 5: GetTrustLevel(TrustLevel* trustLevel) → HRESULT
 *
 * IInspectable IID: {AF86E2E0-B12D-4C6A-9C5A-D7AA65101E90}
 *
 * All WinRT interface methods start at slot 6.
 */
public class IInspectable extends COMObject {

    /** IID of IInspectable itself. */
    public static final UUID IID = UUID.fromString( "AF86E2E0-B12D-4C6A-9C5A-D7AA65101E90" );

    private static final int SLOT_GET_IIDS              = 3;
    private static final int SLOT_GET_RUNTIME_CLASS_NAME = 4;
    private static final int SLOT_GET_TRUST_LEVEL        = 5;

    private static final FunctionDescriptor HR_THIS_ADDR =
        FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS );

    public IInspectable( final MemorySegment ptr, final Arena arena ) { super( ptr, arena ); }

    // ── IInspectable methods ─────────────────────────────────────────────────

    /**
     * Get the runtime class name of this WinRT object.
     * e.g. "Windows.Devices.Bluetooth.BluetoothLEDevice"
     */
    public String getRuntimeClassName()
    {
        try {
            final MemorySegment ph     = arena.allocate( ValueLayout.ADDRESS );
            final int           hr     = (int) invoke( SLOT_GET_RUNTIME_CLASS_NAME, HR_THIS_ADDR, ph );
            COMRuntime.checkHResult( hr );
            final MemorySegment hs     = ph.get( ValueLayout.ADDRESS, 0 );
            final String        result = COMRuntime.hstringToString( hs, arena );
            COMRuntime.deleteHString( hs );
            return result;
        }
        catch( final Throwable t ) {
            if( t instanceof RuntimeException re ) throw re;
            throw new RuntimeException( t );
        }
    }

    /**
     * Get the trust level of this object.
     * All Windows-provided WinRT objects return FullTrust (2).
     */
    public int getTrustLevel()
    {
        try {
            final MemorySegment pLevel = arena.allocate( ValueLayout.JAVA_INT );
            final int           hr     = (int) invoke( SLOT_GET_TRUST_LEVEL, HR_THIS_ADDR, pLevel );
            COMRuntime.checkHResult( hr );
            return pLevel.get( ValueLayout.JAVA_INT, 0 );
        }
        catch( final Throwable t ) {
            if( t instanceof RuntimeException re ) throw re;
            throw new RuntimeException( t );
        }
    }

    /**
     * Get all interface IIDs implemented by this object.
     * Useful for debugging — shows what interfaces you can QI for.
     */
    public UUID[] getIids()
    {
        try {
            final FunctionDescriptor desc =
                FunctionDescriptor.of( ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS );
            final MemorySegment pCount = arena.allocate( ValueLayout.JAVA_INT );
            final MemorySegment ppIids = arena.allocate( ValueLayout.ADDRESS );
            final int           hr     = (int) invoke( SLOT_GET_IIDS, desc, pCount, ppIids );
            COMRuntime.checkHResult( hr );
            final int           count  = pCount.get( ValueLayout.JAVA_INT, 0 );
            final MemorySegment iids   = ppIids.get( ValueLayout.ADDRESS, 0 ).reinterpret( count * 16L );
            final UUID[]        result = new UUID[count];
            for( int i = 0; i < count; i++ ) {
                final int  base = i * 16;
                final long msb  = ((long) (iids.get( ValueLayout.JAVA_INT_UNALIGNED,   base     ) & 0xFFFFFFFFL) << 32)
                                | ((long) (iids.get( ValueLayout.JAVA_SHORT_UNALIGNED, base + 4 ) & 0xFFFFL)     << 16)
                                |         (iids.get( ValueLayout.JAVA_SHORT_UNALIGNED, base + 6 ) & 0xFFFFL);
                long lsb = 0;
                for( int j = 0; j < 8; j++ )
                    lsb = (lsb << 8) | (iids.get( ValueLayout.JAVA_BYTE, base + 8 + j ) & 0xFFL);
                result[i] = new UUID( msb, lsb );
            }
            return result;
        }
        catch( final Throwable t ) {
            if( t instanceof RuntimeException re ) throw re;
            throw new RuntimeException( t );
        }
    }
}
