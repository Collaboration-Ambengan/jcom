/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom.winrt;

import jcom.com.*;
import jcom.winmd.*;

import java.io.IOException;
import java.lang.foreign.*;
import java.nio.file.Path;
import java.util.*;

/**
 * Combines WinMDReader (metadata) + COMObject (vtable dispatch) to
 * resolve WinRT interface methods at runtime without any hardcoded
 * vtable slot indices or GUIDs.
 *
 * Usage:
 * <pre>
 *   WinRTResolver resolver = WinRTResolver.create(); // loads Windows.winmd + RoInitialize
 *
 *   COMObject device = resolver.activate(
 *       "Windows.Devices.Bluetooth.BluetoothLEDevice");
 *
 *   COMObject gattDevice = resolver.queryInterface(device,
 *       "Windows.Devices.Bluetooth.IBluetoothLEDevice");
 *
 *   COMMethod getServices = resolver.method(
 *       "Windows.Devices.Bluetooth.IBluetoothLEDevice",
 *       "GetGattServicesAsync",
 *       FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
 *
 *   getServices.invoke(gattDevice, resultPtr);
 * </pre>
 */
public class WinRTResolver {

    private final WinMDReader winmd;
    private final Arena       arena;

    private WinRTResolver( final WinMDReader winmd, final Arena arena )
    {
        this.winmd = winmd;
        this.arena = arena;
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    /**
     * Create a WinRTResolver by loading Windows.winmd from the default location
     * and initializing the Windows Runtime (RoInitialize MULTITHREADED).
     */
    public static WinRTResolver create() throws IOException
    {
        final WinMDReader reader = new WinMDReader();
        reader.load( WinMDLocator.find() );
        final Arena arena = Arena.ofShared();
        COMRuntime.initialize( COMRuntime.RO_INIT_MULTITHREADED );
        return new WinRTResolver( reader, arena );
    }

    /** Create with an explicit .winmd path (useful for testing with a copied .winmd). */
    public static WinRTResolver create( final Path winmdPath ) throws IOException
    {
        final WinMDReader reader = new WinMDReader();
        reader.load( winmdPath );
        final Arena arena = Arena.ofShared();
        COMRuntime.initialize( COMRuntime.RO_INIT_MULTITHREADED );
        return new WinRTResolver( reader, arena );
    }

    // ── Activation ───────────────────────────────────────────────────────────

    /**
     * Activate a WinRT class and return it as IInspectable.
     *
     * @param runtimeClassName e.g. "Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher"
     */
    public IInspectable activate( final String runtimeClassName )
    {
        return new IInspectable( COMRuntime.activateInstance( runtimeClassName, arena ), arena );
    }

    // ── QueryInterface ───────────────────────────────────────────────────────

    /**
     * QueryInterface for a specific WinRT interface by full name.
     * The IID is looked up from the .winmd — no hardcoding.
     *
     * @return COMObject wrapping the requested interface, or null if not supported
     */
    public COMObject queryInterface( final COMObject obj, final String interfaceName )
    {
        return obj.queryInterface( winmd.getInterface( interfaceName ).guid );
    }

    /**
     * QueryInterface searching all versions of an interface (newest first).
     * Returns the first version that the object implements.
     */
    public COMObject queryInterfaceAnyVersion( final COMObject obj,
        final String namespace, final String baseName )
    {
        final List<WinMDInterface> versions = winmd.findAllVersions( baseName );
        for( int i = versions.size() - 1; i >= 0; i-- ) {
            final WinMDInterface iface = versions.get( i );
            if( !namespace.equals( iface.namespace ) ) continue;
            final COMObject result = obj.queryInterface( iface.guid );
            if( result != null ) return result;
        }
        return null;
    }

    // ── Method resolution ────────────────────────────────────────────────────

    /**
     * Resolve a COMMethod for the given interface method.
     * Slot index comes from the .winmd — not hardcoded.
     *
     * @param interfaceName fully qualified interface name
     * @param methodName    exact method name as in the .winmd
     * @param descriptor    FFM FunctionDescriptor
     */
    public COMMethod method( final String interfaceName, final String methodName,
        final FunctionDescriptor descriptor )
    {
        final int slot = winmd.getInterface( interfaceName ).slotOf( methodName );
        return new COMMethod( methodName, slot, descriptor );
    }

    /**
     * Resolve a method by searching all versions of an interface.
     * Useful when the method may be on IGattCharacteristic2 or IGattCharacteristic3
     * rather than IGattCharacteristic.
     */
    public COMMethod findMethod( final String namespace, final String baseName,
        final String methodName, final FunctionDescriptor descriptor )
    {
        for( final WinMDInterface iface : winmd.findAllVersions( baseName ) ) {
            if( !namespace.equals( iface.namespace ) ) continue;
            if( iface.methods.contains( methodName ) )
                return new COMMethod( methodName, iface.slotOf( methodName ), descriptor );
        }
        throw new IllegalArgumentException(
            "Method not found in any version of " + namespace + "." + baseName + ": " + methodName );
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** Access the underlying WinMDReader for direct interface inspection. */
    public WinMDReader winmd() { return winmd; }

    /** The shared arena for COM object allocations. */
    public Arena       arena() { return arena; }

    public void close()
    {
        arena.close();
        COMRuntime.uninitialize();
    }
}
