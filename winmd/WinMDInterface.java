/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom.winmd;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents one WinRT interface extracted from a .winmd file.
 *
 * Contains everything needed by WinRTResolver to:
 *   1. QueryInterface using the GUID
 *   2. Compute vtable slot indices for each method
 *
 * Immutable — created by WinMDReader and cached.
 */
public final class WinMDInterface {

    /** Fully qualified interface name, e.g. "Windows.Devices.Bluetooth.GenericAttributeProfile.IGattCharacteristic". */
    public final String name;

    /** Simple name, e.g. "IGattCharacteristic". */
    public final String simpleName;

    /** Namespace, e.g. "Windows.Devices.Bluetooth.GenericAttributeProfile". */
    public final String namespace;

    /**
     * COM interface GUID from GuidAttribute.
     * Used as the IID in QueryInterface calls.
     * Null if GuidAttribute was not found (should not happen for WinRT interfaces).
     */
    public final UUID guid;

    /**
     * Method names in vtable declaration order.
     * Index 0 = first method after IInspectable base (absolute vtable slot 6).
     * Index n = absolute vtable slot 6+n.
     *
     * This list is immutable.
     */
    public final List<String> methods;

    public WinMDInterface( final String namespace, final String simpleName,
        final UUID guid, final List<String> methods )
    {
        this.namespace  = namespace;
        this.simpleName = simpleName;
        this.name       = namespace.isEmpty() ? simpleName : namespace + "." + simpleName;
        this.guid       = guid;
        this.methods    = Collections.unmodifiableList( methods );
    }

    /**
     * Return the absolute vtable slot index for the given method name.
     * Slots 0-5 are IUnknown (0-2) and IInspectable (3-5) — not in this list.
     *
     * @param methodName exact method name as it appears in the .winmd
     * @return absolute vtable slot index (&gt;= 6)
     * @throws IllegalArgumentException if method not found
     */
    public int slotOf( final String methodName )
    {
        final int idx = methods.indexOf( methodName );
        if( idx < 0 )
            throw new IllegalArgumentException(
                "Method '" + methodName + "' not found in " + name + ". Available: " + methods );
        return 6 + idx; // slots 0-5 = IUnknown + IInspectable
    }

    /**
     * Return absolute vtable slot of the first method whose name contains the given substring.
     * Useful for versioned method names like "WriteValueAsync" vs "WriteValueWithResultAsync".
     *
     * @throws IllegalArgumentException if no method matches
     */
    public int slotOfContaining( final String methodNameSubstring )
    {
        for( int i = 0; i < methods.size(); i++ )
            if( methods.get( i ).contains( methodNameSubstring ) ) return 6 + i;
        throw new IllegalArgumentException(
            "No method containing '" + methodNameSubstring + "' found in " + name + ". Available: " + methods );
    }

    /** Number of methods defined directly on this interface (excluding base slots). */
    public int methodCount() { return methods.size(); }

    @Override public String toString()
    {
        return "WinMDInterface{" + name + ", guid=" + guid + ", methods=" + methods.size() + "}";
    }
}
