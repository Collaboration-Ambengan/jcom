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

import java.lang.foreign.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Creates dynamic Java proxies backed by COM vtable calls.
 *
 * Instead of manually calling {@code resolver.method(...).invoke(...)}, define a Java
 * interface mirroring the WinRT interface and get a proxy that routes calls through the
 * vtable automatically.
 *
 * Example:
 * <pre>
 *   interface IGattCharacteristic {
 *       int writeValueAsync(MemorySegment value, MemorySegment result);
 *   }
 *
 *   IGattCharacteristic proxy = WinRTProxy.create(
 *       charComObject,
 *       IGattCharacteristic.class,
 *       "Windows.Devices.Bluetooth.GenericAttributeProfile.IGattCharacteristic",
 *       resolver);
 *
 *   proxy.writeValueAsync(bufPtr, resultPtr);
 * </pre>
 *
 * Limitations:
 *   - Method parameter types must be MemorySegment, int, long, or void
 *   - No automatic HRESULT checking — caller interprets the returned int
 *   - No automatic IAsyncOperation unwrapping — use COMMethod.invokeAsync() for that
 */
public class WinRTProxy {

    /**
     * Create a dynamic proxy for a COM object implementing the given Java interface.
     *
     * @param <T>            the Java interface type
     * @param comObject      the underlying COM object
     * @param javaInterface  the Java interface class to proxy
     * @param winrtInterface fully qualified WinRT interface name (for slot lookup)
     * @param resolver       WinRTResolver for slot/GUID lookup
     */
    @SuppressWarnings("unchecked")
    public static <T> T create( final COMObject comObject, final Class<T> javaInterface,
        final String winrtInterface, final WinRTResolver resolver )
    {
        final WinMDInterface iface   = resolver.winmd().getInterface( winrtInterface );
        final WinRTHandler   handler = new WinRTHandler( comObject, iface );
        return (T) Proxy.newProxyInstance(
            javaInterface.getClassLoader(), new Class<?>[]{ javaInterface }, handler );
    }

    // ── InvocationHandler ────────────────────────────────────────────────────

    private static class WinRTHandler implements InvocationHandler {

        private final COMObject              comObject;
        private final WinMDInterface         iface;
        private final Map<String, COMMethod> methodCache = new HashMap<>();

        WinRTHandler( final COMObject comObject, final WinMDInterface iface )
        {
            this.comObject = comObject;
            this.iface     = iface;
        }

        @Override
        public Object invoke( final Object proxy, final Method method, final Object[] args )
            throws Throwable
        {
            switch( method.getName() ) {
                case "toString":  return "WinRTProxy[" + iface.name + "]";
                case "hashCode":  return System.identityHashCode( comObject );
                case "equals":    return proxy == args[0];
            }
            final COMMethod comMethod = methodCache.computeIfAbsent( method.getName(),
                name -> new COMMethod( name, iface.slotOf( name ), buildDescriptor( method ) ) );
            return comMethod.invoke( comObject, args != null ? args : new Object[0] );
        }

        /**
         * Build a FunctionDescriptor from a Java Method's parameter/return types.
         * Prepends ADDRESS for the implicit {@code this} pointer.
         * Mapping: {@code void} → ofVoid, {@code int} → JAVA_INT,
         *          {@code long} → JAVA_LONG, {@code MemorySegment} → ADDRESS.
         */
        private FunctionDescriptor buildDescriptor( final Method method )
        {
            final Class<?>[]    params  = method.getParameterTypes();
            final ValueLayout[] layouts = new ValueLayout[params.length + 1];
            layouts[0] = ValueLayout.ADDRESS; // implicit this
            for( int i = 0; i < params.length; i++ ) layouts[i + 1] = toLayout( params[i] );
            final Class<?> ret = method.getReturnType();
            return ret == void.class
                ? FunctionDescriptor.ofVoid( layouts )
                : FunctionDescriptor.of( toLayout( ret ), layouts );
        }

        private static ValueLayout toLayout( final Class<?> type )
        {
            if( type == int.class )           return ValueLayout.JAVA_INT;
            if( type == long.class )          return ValueLayout.JAVA_LONG;
            if( type == MemorySegment.class ) return ValueLayout.ADDRESS;
            throw new IllegalArgumentException( "Unsupported parameter type: " + type );
        }
    }
}
