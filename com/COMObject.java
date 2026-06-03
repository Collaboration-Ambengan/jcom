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
import java.util.UUID;

/**
 * Base wrapper around a COM/WinRT object pointer (IUnknown* / IInspectable*).
 *
 * Provides QueryInterface and ref-counting (AddRef/Release).
 * All WinRT objects are accessed via vtable pointers — this class
 * encapsulates the vtable dispatch mechanics.
 *
 * COM vtable layout (every COM object):
 *   slot 0: QueryInterface(REFIID riid, void** ppvObject) → HRESULT
 *   slot 1: AddRef()  → ULONG
 *   slot 2: Release() → ULONG
 *   --- IInspectable adds: ---
 *   slot 3: GetIids(ULONG* iidCount, IID** iids) → HRESULT
 *   slot 4: GetRuntimeClassName(HSTRING* className) → HRESULT
 *   slot 5: GetTrustLevel(TrustLevel* trustLevel) → HRESULT
 *   --- WinRT interface methods start at slot 6 ---
 */
public class COMObject implements AutoCloseable {

    protected final MemorySegment ptr;   // the COM object pointer (IUnknown*)
    protected final Arena         arena; // arena for short-lived allocations
    private   final Linker        linker = Linker.nativeLinker();

    /**
     * Wrap an existing COM object pointer.
     * Does NOT call AddRef — assumes caller transfers ownership.
     */
    public COMObject( final MemorySegment ptr, final Arena arena )
    {
        this.ptr   = ptr;
        this.arena = arena;
    }

    // ── IUnknown ─────────────────────────────────────────────────────────────

    /**
     * QueryInterface for a different interface pointer.
     *
     * @param iid the interface IID as a UUID
     * @return new COMObject wrapping the requested interface, or null if E_NOINTERFACE
     */
    public COMObject queryInterface( final UUID iid )
    {
        try {
            final MemorySegment iidSeg = iidToSegment( iid );
            final MemorySegment ppv    = arena.allocate( ValueLayout.ADDRESS );
            final int           hr     = (int) invoke( 0,
                FunctionDescriptor.of( ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS ),
                iidSeg, ppv );
            if( hr == COMRuntime.E_NOINTERFACE ) return null;
            COMRuntime.checkHResult( hr );
            return new COMObject( ppv.get( ValueLayout.ADDRESS, 0 ), arena );
        }
        catch( final Throwable t ) {
            if( t instanceof RuntimeException re ) throw re;
            throw new RuntimeException( t );
        }
    }

    /** Increment reference count (vtable slot 1). */
    public void addRef()
    {
        try { invoke( 1, FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) ); }
        catch( final Throwable t ) {
            if( t instanceof RuntimeException re ) throw re;
            throw new RuntimeException( t );
        }
    }

    /** Decrement reference count (vtable slot 2). Object is destroyed when count reaches 0. */
    public void release()
    {
        try { invoke( 2, FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) ); }
        catch( final Throwable t ) {
            if( t instanceof RuntimeException re ) throw re;
            throw new RuntimeException( t );
        }
    }

    /** AutoCloseable — calls release(). */
    @Override public void close() { release(); }

    // ── Vtable dispatch ──────────────────────────────────────────────────────

    /**
     * Get a MethodHandle for the function at vtable slot N.
     *
     * COM vtable layout: ptr[0] = vtable array pointer; vtable[slot] = function pointer.
     *
     * @param slot       absolute vtable slot index (0=QI, 1=AddRef, 2=Release, 6+=interface methods)
     * @param descriptor FFM FunctionDescriptor matching the function signature
     */
    public MethodHandle vtableMethod( final int slot, final FunctionDescriptor descriptor )
    {
        final MemorySegment vtable = ptr.get( ValueLayout.ADDRESS, 0 ).reinterpret( Long.MAX_VALUE );
        final MemorySegment fnPtr  = vtable.getAtIndex( ValueLayout.ADDRESS, slot );
        return linker.downcallHandle( fnPtr, descriptor );
    }

    /**
     * Resolve and invoke vtable slot, prepending {@code ptr} as the implicit {@code this}.
     */
    public Object invoke( final int slot, final FunctionDescriptor descriptor, final Object... args )
        throws Throwable
    {
        final Object[] all = new Object[args.length + 1];
        all[0] = ptr;
        System.arraycopy( args, 0, all, 1, args.length );
        return vtableMethod( slot, descriptor ).invokeWithArguments( all );
    }

    // ── IID helpers ──────────────────────────────────────────────────────────

    /**
     * Convert a UUID to a 16-byte Windows GUID MemorySegment.
     *
     * Windows GUID layout:
     *   bytes 0-3:  Data1 (uint32 LE) = uuid.getMostSignificantBits() &gt;&gt;&gt; 32
     *   bytes 4-5:  Data2 (uint16 LE) = uuid.getMostSignificantBits() &gt;&gt;&gt; 16
     *   bytes 6-7:  Data3 (uint16 LE) = uuid.getMostSignificantBits()
     *   bytes 8-15: Data4 (8 bytes BE) = uuid.getLeastSignificantBits()
     */
    public MemorySegment iidToSegment( final UUID iid )
    {
        final MemorySegment seg = arena.allocate( 16 );
        final long          msb = iid.getMostSignificantBits();
        final long          lsb = iid.getLeastSignificantBits();
        seg.set( ValueLayout.JAVA_INT_UNALIGNED,   0, (int)   (msb >>> 32) ); // Data1 LE
        seg.set( ValueLayout.JAVA_SHORT_UNALIGNED, 4, (short) (msb >>> 16) ); // Data2 LE
        seg.set( ValueLayout.JAVA_SHORT_UNALIGNED, 6, (short)  msb         ); // Data3 LE
        for( int i = 0; i < 8; i++ )                                          // Data4 BE
            seg.set( ValueLayout.JAVA_BYTE, 8 + i, (byte) (lsb >>> (56 - i * 8)) );
        return seg;
    }

    /** Return the raw COM object pointer. Use with care. */
    public MemorySegment pointer() { return ptr; }

    @Override public String toString()
    {
        return getClass().getSimpleName() + "@" + Long.toHexString( ptr.address() );
    }
}
