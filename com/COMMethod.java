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

/**
 * Represents a single WinRT interface method, resolved from a vtable slot.
 *
 * Combines:
 *   - The vtable slot index (from WinMDReader at runtime)
 *   - The FFM FunctionDescriptor (parameter/return types)
 *   - A cached MethodHandle (lazily resolved on first call)
 *
 * Usage:
 * <pre>
 *   COMMethod writeValue = new COMMethod(
 *       "WriteValueAsync",
 *       slot,                    // from WinMDInterface.slotOf("WriteValueAsync")
 *       FunctionDescriptor.of(   // HRESULT WriteValueAsync(IBuffer* value, IAsyncOp** result)
 *           ValueLayout.JAVA_INT,
 *           ValueLayout.ADDRESS,  // this
 *           ValueLayout.ADDRESS,  // value (IBuffer*)
 *           ValueLayout.ADDRESS   // result (IAsyncOperation&lt;GattWriteResult&gt;**)
 *       )
 *   );
 *   int hr = (int) writeValue.invoke(comObject, bufferPtr, resultPtr);
 * </pre>
 */
public class COMMethod {

    private final String             name;
    private final int                vtableSlot;
    private final FunctionDescriptor descriptor;

    /** Cached handle — lazily resolved on first invoke(). */
    private MethodHandle handle;

    public COMMethod( final String name, final int vtableSlot, final FunctionDescriptor descriptor )
    {
        this.name       = name;
        this.vtableSlot = vtableSlot;
        this.descriptor = descriptor;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Invoke this method on a COMObject.
     * The {@code ptr} (this pointer) is prepended automatically.
     *
     * @param obj  the COM object to call the method on
     * @param args additional arguments after {@code this}
     * @return the raw return value (cast by caller), or null for void methods
     */
    public Object invoke( final COMObject obj, final Object... args ) throws Throwable
    {
        if( handle == null ) handle = obj.vtableMethod( vtableSlot, descriptor );
        final Object[] all = new Object[args.length + 1];
        all[0] = obj.pointer();
        System.arraycopy( args, 0, all, 1, args.length );
        return handle.invokeWithArguments( all );
    }

    /**
     * Invoke and check the returned HRESULT.
     * Convenience for the majority of COM methods that return HRESULT.
     */
    public void invokeChecked( final COMObject obj, final Object... args ) throws Throwable
    {
        COMRuntime.checkHResult( (int) invoke( obj, args ) );
    }

    /**
     * Invoke an async WinRT method that returns {@code IAsyncOperation<T>}.
     *
     * Polls {@code IAsyncInfo.get_Status()} (slot 7) until the operation completes,
     * then calls {@code IAsyncOperation.GetResults()} (slot 8).
     * Throws on error or cancellation.  Times out after 10 seconds.
     *
     * IAsyncInfo vtable slots (via QI):
     *   slot 6: get_Id, slot 7: get_Status, slot 8: get_ErrorCode, slot 9: Cancel, slot 10: Close
     *   AsyncStatus: 0=Started, 1=Completed, 2=Canceled, 3=Error
     *
     * @param obj          the COM object to call on
     * @param asyncOpIid   IID of the specific {@code IAsyncOperation<T>} interface
     * @param asyncInfoIid IID of {@code IAsyncInfo}
     * @param args         additional args to the method call
     * @return COMObject wrapping the GetResults() output
     */
    public COMObject invokeAsync( final COMObject obj, final java.util.UUID asyncOpIid,
        final java.util.UUID asyncInfoIid, final Object... args ) throws Throwable
    {
        // Call the method → get IAsyncOperation* output pointer
        final MemorySegment ppOp    = obj.arena.allocate( ValueLayout.ADDRESS );
        final Object[]      allArgs = new Object[args.length + 1];
        System.arraycopy( args, 0, allArgs, 0, args.length );
        allArgs[args.length] = ppOp;
        invokeChecked( obj, allArgs );

        final COMObject asyncOp   = new COMObject( ppOp.get( ValueLayout.ADDRESS, 0 ), obj.arena );
        final COMObject asyncInfo = asyncOp.queryInterface( asyncInfoIid );

        final FunctionDescriptor intIntAddr = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS );

        // Poll until not AsyncStatus.Started (0)
        final long deadline = System.currentTimeMillis() + 10_000;
        while( System.currentTimeMillis() < deadline ) {
            final MemorySegment pStatus = obj.arena.allocate( ValueLayout.JAVA_INT );
            asyncInfo.invoke( 7, intIntAddr, pStatus );
            final int status = pStatus.get( ValueLayout.JAVA_INT, 0 );
            if( status == 1 ) break; // Completed
            if( status == 2 ) throw new java.util.concurrent.CancellationException( "WinRT async canceled" );
            if( status == 3 ) {     // Error
                final MemorySegment pErr = obj.arena.allocate( ValueLayout.JAVA_INT );
                asyncInfo.invoke( 8, intIntAddr, pErr );
                COMRuntime.checkHResult( pErr.get( ValueLayout.JAVA_INT, 0 ) );
            }
            Thread.sleep( 1 );
        }
        asyncInfo.release();

        // GetResults — slot 8 on IAsyncOperation<T>
        final MemorySegment ppResult = obj.arena.allocate( ValueLayout.ADDRESS );
        final COMObject     typedOp  = asyncOp.queryInterface( asyncOpIid );
        typedOp.invoke( 8, intIntAddr, ppResult );
        typedOp.release();
        asyncOp.release();
        return new COMObject( ppResult.get( ValueLayout.ADDRESS, 0 ), obj.arena );
    }

    /** Vtable slot index for this method. */
    public int    slot() { return vtableSlot; }

    /** Method name (for debugging). */
    public String name() { return name; }

    @Override public String toString() { return "COMMethod{" + name + ", slot=" + vtableSlot + "}"; }
}
