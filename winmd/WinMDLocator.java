/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom.winmd;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Locates Windows.winmd on the host machine.
 *
 * Search order:
 *   1. System property "jcom.winmd.path" — explicit override
 *   2. Environment variable WINMD_PATH — CI/container override
 *   3. Standard Windows SDK UnionMetadata path (Windows only):
 *      C:\Program Files (x86)\Windows Kits\10\UnionMetadata\&lt;version&gt;\Windows.winmd
 *      Picks the newest version directory found.
 *   4. NuGet package cache (cross-platform — for Linux dev):
 *      ~/.nuget/packages/microsoft.windows.sdk.contracts/&lt;version&gt;/ref/netstandard2.0/Windows.winmd
 *   5. Current working directory: ./Windows.winmd
 */
public class WinMDLocator {

    private static final String WINDOWS_SDK_BASE = "C:\\Program Files (x86)\\Windows Kits\\10\\UnionMetadata";
    private static final String NUGET_PACKAGE    = "microsoft.windows.sdk.contracts";
    private static final String PROP_WINMD_PATH  = "jcom.winmd.path";
    private static final String ENV_WINMD_PATH   = "WINMD_PATH";

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Find Windows.winmd using the 5-step search order described in the class javadoc.
     *
     * @return path to Windows.winmd
     * @throws IOException if Windows.winmd cannot be found anywhere
     */
    public static Path find() throws IOException
    {
        final List<Path> tried = new ArrayList<>();

        // 1. System property override
        final String prop = System.getProperty( PROP_WINMD_PATH );
        if( prop != null ) {
            final Path p = Path.of( prop );
            if( Files.isReadable( p ) ) return p;
            tried.add( p );
        }

        // 2. Environment variable override
        final String env = System.getenv( ENV_WINMD_PATH );
        if( env != null ) {
            final Path p = Path.of( env );
            if( Files.isReadable( p ) ) return p;
            tried.add( p );
        }

        // 3. Windows SDK UnionMetadata
        final Path sdk = findWindowsSdk();
        if( sdk != null ) return sdk;
        tried.add( Path.of( WINDOWS_SDK_BASE, "<version>", "Windows.winmd" ) );

        // 4. NuGet package cache
        final Path nuget = findNugetCache();
        if( nuget != null ) return nuget;
        tried.add( Path.of( System.getProperty( "user.home" ),
            ".nuget", "packages", NUGET_PACKAGE, "<version>",
            "ref", "netstandard2.0", "Windows.winmd" ) );

        // 5. Current working directory
        final Path cwd = Path.of( "Windows.winmd" );
        if( Files.isReadable( cwd ) ) return cwd;
        tried.add( cwd.toAbsolutePath() );

        throw new IOException(
            "Windows.winmd not found. Tried:\n"
            + tried.stream().map( p -> "  " + p ).reduce( "", (a, b) -> a + b + "\n" )
            + "Set system property -Djcom.winmd.path=<path> or env WINMD_PATH=<path>" );
    }

    /**
     * Find Windows.winmd for a specific SDK version.
     *
     * @param version e.g. "10.0.22621.0"
     * @throws IOException if that version is not installed
     */
    public static Path findVersion( final String version ) throws IOException
    {
        final Path p = Path.of( WINDOWS_SDK_BASE, version, "Windows.winmd" );
        if( !Files.isReadable( p ) )
            throw new IOException( "Windows.winmd not found for version " + version + ": " + p );
        return p;
    }

    /**
     * List all installed Windows SDK versions that have Windows.winmd.
     * Returns empty list if not on Windows or SDK not installed.
     * Sorted newest first.
     */
    public static List<String> installedVersions()
    {
        final Path base = Path.of( WINDOWS_SDK_BASE );
        if( !Files.isDirectory( base ) ) return List.of();
        try( final Stream<Path> dirs = Files.list( base ) ) {
            return dirs
                .filter( Files::isDirectory )
                .filter( d -> Files.isReadable( d.resolve( "Windows.winmd" ) ) )
                .map( d -> d.getFileName().toString() )
                .sorted( (a, b) -> compareVersions( b, a ) )
                .toList();
        }
        catch( final IOException e ) {
            return List.of();
        }
    }

    // ── Private search helpers ───────────────────────────────────────────────

    private static Path findWindowsSdk()
    {
        final Path base = Path.of( WINDOWS_SDK_BASE );
        if( !Files.isDirectory( base ) ) return null;
        try( final Stream<Path> dirs = Files.list( base ) ) {
            return dirs
                .filter( Files::isDirectory )
                .sorted( (a, b) -> compareVersions( b.getFileName().toString(), a.getFileName().toString() ) )
                .map( d -> d.resolve( "Windows.winmd" ) )
                .filter( p -> Files.isReadable( p ) )
                .findFirst().orElse( null );
        }
        catch( final IOException e ) {
            return null;
        }
    }

    private static Path findNugetCache()
    {
        final Path nugetBase = Path.of( System.getProperty( "user.home" ), ".nuget", "packages", NUGET_PACKAGE );
        if( !Files.isDirectory( nugetBase ) ) return null;
        try( final Stream<Path> vers = Files.list( nugetBase ) ) {
            return vers
                .filter( Files::isDirectory )
                .sorted( (a, b) -> compareVersions( b.getFileName().toString(), a.getFileName().toString() ) )
                .map( d -> d.resolve( "ref" ).resolve( "netstandard2.0" ).resolve( "Windows.winmd" ) )
                .filter( p -> Files.isReadable( p ) )
                .findFirst().orElse( null );
        }
        catch( final IOException e ) {
            return null;
        }
    }

    /**
     * Compare two Windows SDK version strings (e.g. "10.0.22621.0" vs "10.0.26100.0").
     * Returns positive if v1 &gt; v2 (v1 is newer).  Missing components treated as 0.
     */
    static int compareVersions( final String v1, final String v2 )
    {
        final String[] a   = v1.split( "\\.", -1 );
        final String[] b   = v2.split( "\\.", -1 );
        final int      len = Math.max( a.length, b.length );
        for( int i = 0; i < len; i++ ) {
            final int x = i < a.length ? Integer.parseInt( a[i] ) : 0;
            final int y = i < b.length ? Integer.parseInt( b[i] ) : 0;
            if( x != y ) return x - y;
        }
        return 0;
    }
}
