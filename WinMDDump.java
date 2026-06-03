/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom;

import jcom.winmd.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Standalone test tool — verifies the ECMA-335 parser against a real Windows.winmd.
 * Runs on Linux with a copied Windows.winmd — no Windows required.
 *
 * Usage:
 *   java jcom.WinMDDump [path/to/Windows.winmd]
 *   java jcom.WinMDDump   (uses WinMDLocator.find() to auto-locate)
 *
 * Use the output to verify slot indices before writing any COM calls.
 * Cross-check against ildasm/dotnet-dump on Windows or the .winmd browser at
 * https://learn.microsoft.com/uwp/api/
 */
public class WinMDDump {

    // BLE-relevant namespaces to dump in detail
    private static final List<String> BLE_NAMESPACES = List.of(
        "Windows.Devices.Bluetooth",
        "Windows.Devices.Bluetooth.Advertisement",
        "Windows.Devices.Bluetooth.GenericAttributeProfile"
    );

    public static void main( final String[] args ) throws IOException
    {
        final Path winmdPath;
        if( args.length > 0 ) {
            winmdPath = Path.of( args[0] );
            System.out.println( "Using specified path: " + winmdPath );
        } else {
            System.out.println( "Auto-locating Windows.winmd..." );
            winmdPath = WinMDLocator.find();
        }

        System.out.println( "Loading: " + winmdPath );
        System.out.printf( "File size: %.1f MB%n", Files.size( winmdPath ) / 1_048_576.0 );

        final long        start   = System.currentTimeMillis();
        final WinMDReader reader  = new WinMDReader();
        reader.load( winmdPath );
        final long elapsed = System.currentTimeMillis() - start;

        System.out.printf( "Loaded %d WinRT interfaces in %d ms%n%n",
            reader.allInterfaceNames().size(), elapsed );

        // Dump BLE interfaces in detail
        System.out.println( "=== BLE interfaces ===" );
        for( final String ns : BLE_NAMESPACES ) {
            final List<WinMDInterface> ifaces = reader.interfacesInNamespace( ns );
            if( ifaces.isEmpty() ) {
                System.out.println( "[no interfaces found in " + ns + "]" );
                continue;
            }
            System.out.println( "\nNamespace: " + ns );
            for( final WinMDInterface iface : ifaces ) {
                System.out.println( "  " + iface.simpleName );
                System.out.println( "    GUID: " + iface.guid );
                System.out.println( "    Methods (" + iface.methodCount() + "):" );
                for( int i = 0; i < iface.methods.size(); i++ )
                    System.out.printf( "      slot %2d: %s%n", 6 + i, iface.methods.get( i ) );
            }
        }

        // Specifically verify the interfaces our BLE code needs
        System.out.println( "\n=== BLE method slot verification ===" );
        verifyMethod( reader, "Windows.Devices.Bluetooth.GenericAttributeProfile",
            "IGattCharacteristic", "WriteValueAsync" );
        verifyMethod( reader, "Windows.Devices.Bluetooth.GenericAttributeProfile",
            "IGattCharacteristic", "WriteClientCharacteristicConfigurationDescriptorAsync" );
        verifyMethod( reader, "Windows.Devices.Bluetooth.GenericAttributeProfile",
            "IGattCharacteristic", "add_ValueChanged" );
        verifyMethod( reader, "Windows.Devices.Bluetooth.Advertisement",
            "IBluetoothLEAdvertisementWatcher", "Start" );
        verifyMethod( reader, "Windows.Devices.Bluetooth.Advertisement",
            "IBluetoothLEAdvertisementWatcher", "add_Received" );
        verifyMethod( reader, "Windows.Devices.Bluetooth",
            "IBluetoothLEDevice", "GetGattServicesAsync" );
    }

    private static void verifyMethod( final WinMDReader reader,
        final String namespace, final String baseName, final String methodName )
    {
        try {
            final List<WinMDInterface> versions = reader.findAllVersions( baseName );
            if( versions.isEmpty() ) {
                System.out.println( "  NOT FOUND: " + baseName );
                return;
            }
            for( final WinMDInterface iface : versions ) {
                if( iface.methods.contains( methodName ) ) {
                    System.out.printf( "  %-50s slot %2d  (%s)%n",
                        iface.simpleName + "." + methodName,
                        iface.slotOf( methodName ), iface.name );
                    return;
                }
            }
            System.out.println( "  METHOD NOT FOUND: " + baseName + "." + methodName
                + " (checked " + versions.size() + " versions)" );
        }
        catch( final Exception e ) {
            System.out.println( "  ERROR: " + baseName + "." + methodName + " → " + e.getMessage() );
        }
    }
}
