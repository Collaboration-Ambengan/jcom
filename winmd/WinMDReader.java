/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom.winmd;

import jcom.ecma335.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;

/**
 * High-level reader that extracts WinRT interface metadata from a .winmd file.
 *
 * Builds on CliMetadataReader + MetadataTables + HeapReader to produce
 * WinMDInterface objects — one per WinRT interface in the file.
 *
 * Usage:
 * <pre>
 *   WinMDReader reader = new WinMDReader();
 *   reader.load(WinMDLocator.find());
 *
 *   WinMDInterface iface = reader.getInterface(
 *       "Windows.Devices.Bluetooth.GenericAttributeProfile.IGattCharacteristic");
 *   int slot = iface.slotOf("WriteValueAsync");  // e.g. 16
 *   UUID iid  = iface.guid;
 * </pre>
 */
public class WinMDReader {

    // ── Parsed tables (populated by load()) ─────────────────────────────────
    private CliMetadataReader meta;
    private MetadataTables    tables;
    private HeapReader        heaps;

    /** All WinRT interfaces keyed by full name. Populated by buildInterfaces(). */
    private final Map<String, WinMDInterface>          interfacesByName = new LinkedHashMap<>();

    /** TypeDef rows keyed by 1-based row index (WinRT interfaces only). */
    private final Map<Integer, TableRows.TypeDefRow>   typeDefs         = new HashMap<>();

    /** MethodDef rows keyed by 1-based row index. */
    private final Map<Integer, TableRows.MethodDefRow> methodDefs       = new HashMap<>();

    /** TypeRef rows keyed by 1-based row index. */
    private final Map<Integer, TableRows.TypeRefRow>   typeRefs         = new HashMap<>();

    /** MemberRef rows keyed by 1-based row index. */
    private final Map<Integer, TableRows.MemberRefRow> memberRefs       = new HashMap<>();

    /** GUIDs keyed by TypeDef row index. Populated by loadCustomAttributes(). */
    private final Map<Integer, UUID>                   typeDefGuids     = new HashMap<>();

    /**
     * methodListIndex for every TypeDef row (including non-WinRT ones), sorted by row index.
     * Needed to compute method ranges in buildInterfaces().
     */
    private final TreeMap<Integer, Integer>            allMethodLists   = new TreeMap<>();

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Load and parse the .winmd file at the given path.
     * After this returns, all getInterface() calls are available.
     */
    public void load( final Path winmdPath ) throws IOException
    {
        meta   = new CliMetadataReader(); meta.load( winmdPath );
        tables = new MetadataTables( meta ); tables.parseHeader();
        heaps  = new HeapReader( meta );
        loadTypeRefs();
        loadMemberRefs();
        loadTypeDefs();
        loadMethodDefs();
        loadCustomAttributes();
        buildInterfaces();
    }

    /**
     * Get an interface by fully qualified name.
     *
     * @param fullName e.g. "Windows.Devices.Bluetooth.GenericAttributeProfile.IGattCharacteristic"
     * @throws IllegalArgumentException if not found
     */
    public WinMDInterface getInterface( final String fullName )
    {
        final WinMDInterface iface = interfacesByName.get( fullName );
        if( iface == null )
            throw new IllegalArgumentException(
                "Interface not found: " + fullName
                + "\nKnown interfaces in same namespace: "
                + interfacesByName.keySet().stream()
                    .filter( k -> k.startsWith( fullName.substring( 0, fullName.lastIndexOf( '.' ) + 1 ) ) )
                    .toList() );
        return iface;
    }

    /**
     * Find an interface by simple name (case-sensitive).
     * Returns the first match; use findAllVersions() to get all versioned variants.
     */
    public WinMDInterface findInterface( final String simpleName )
    {
        for( final WinMDInterface i : interfacesByName.values() )
            if( simpleName.equals( i.simpleName ) ) return i;
        return null;
    }

    /**
     * Find all versioned interfaces for a base name, e.g. "IGattCharacteristic" returns
     * [IGattCharacteristic, IGattCharacteristic2, IGattCharacteristic3] in version order.
     */
    public List<WinMDInterface> findAllVersions( final String baseName )
    {
        return interfacesByName.values().stream()
            .filter( i -> {
                final String s      = i.simpleName;
                if( !s.startsWith( baseName ) ) return false;
                final String suffix = s.substring( baseName.length() );
                return suffix.isEmpty() || suffix.matches( "\\d+" );
            } )
            .sorted( Comparator.comparingInt( (WinMDInterface i) -> i.simpleName.length() )
                .thenComparing( i -> i.simpleName ) )
            .toList();
    }

    /** Return all known interface names. */
    public Set<String> allInterfaceNames()
    {
        return Collections.unmodifiableSet( interfacesByName.keySet() );
    }

    /** Return all interfaces in the given namespace. */
    public List<WinMDInterface> interfacesInNamespace( final String namespace )
    {
        return interfacesByName.values().stream()
            .filter( i -> namespace.equals( i.namespace ) )
            .toList();
    }

    // ── Private table loaders ────────────────────────────────────────────────

    private void loadTypeRefs()
    {
        final ByteBuffer buf        = meta.buffer();
        final int        count      = tables.rowCounts[MetadataTables.TBL_TYPE_REF];
        buf.position( tables.tableOffset( MetadataTables.TBL_TYPE_REF ) );
        final int scopeWidth = tables.codedIndexSize( MetadataTables.CI_RESOLUTION_SCOPE );
        final int strWidth   = tables.stringsIndexSize();
        for( int i = 1; i <= count; i++ ) {
            final int scope   = heaps.readIndex( scopeWidth );
            final int nameIdx = heaps.readIndex( strWidth );
            final int nsIdx   = heaps.readIndex( strWidth );
            final int nextRow = buf.position();
            typeRefs.put( i, new TableRows.TypeRefRow( i, scope,
                heaps.readString( nameIdx ), heaps.readString( nsIdx ) ) );
            buf.position( nextRow );
        }
    }

    private void loadMemberRefs()
    {
        final ByteBuffer buf        = meta.buffer();
        final int        count      = tables.rowCounts[MetadataTables.TBL_MEMBER_REF];
        buf.position( tables.tableOffset( MetadataTables.TBL_MEMBER_REF ) );
        final int classWidth = tables.codedIndexSize( MetadataTables.CI_MEMBER_REF_PARENT );
        final int strWidth   = tables.stringsIndexSize();
        final int blobWidth  = tables.blobIndexSize();
        for( int i = 1; i <= count; i++ ) {
            final int cls     = heaps.readIndex( classWidth );
            final int nameIdx = heaps.readIndex( strWidth );
            final int sig     = heaps.readIndex( blobWidth );
            final int nextRow = buf.position();
            memberRefs.put( i, new TableRows.MemberRefRow( i, cls,
                heaps.readString( nameIdx ), sig ) );
            buf.position( nextRow );
        }
    }

    private void loadTypeDefs()
    {
        final ByteBuffer buf          = meta.buffer();
        final int        count        = tables.rowCounts[MetadataTables.TBL_TYPE_DEF];
        buf.position( tables.tableOffset( MetadataTables.TBL_TYPE_DEF ) );
        final int strWidth     = tables.stringsIndexSize();
        final int extendsWidth = tables.codedIndexSize( MetadataTables.CI_TYPE_DEF_OR_REF );
        final int fieldWidth   = tables.tableIndexSize( MetadataTables.TBL_FIELD );
        final int methodWidth  = tables.tableIndexSize( MetadataTables.TBL_METHOD_DEF );
        for( int i = 1; i <= count; i++ ) {
            final int                  flags   = buf.getInt();
            final int                  nameIdx = heaps.readIndex( strWidth );
            final int                  nsIdx   = heaps.readIndex( strWidth );
            final int                  ext     = heaps.readIndex( extendsWidth );
            final int                  field   = heaps.readRowIndex( fieldWidth );
            final int                  meth    = heaps.readRowIndex( methodWidth );
            final int                  nextRow = buf.position();
            final TableRows.TypeDefRow row     = new TableRows.TypeDefRow( i, flags,
                heaps.readString( nameIdx ), heaps.readString( nsIdx ), ext, field, meth );
            buf.position( nextRow );
            allMethodLists.put( i, meth );
            if( row.isInterface() && row.isWindowsRuntime() ) typeDefs.put( i, row );
        }
    }

    private void loadMethodDefs()
    {
        final ByteBuffer buf        = meta.buffer();
        final int        count      = tables.rowCounts[MetadataTables.TBL_METHOD_DEF];
        buf.position( tables.tableOffset( MetadataTables.TBL_METHOD_DEF ) );
        final int strWidth   = tables.stringsIndexSize();
        final int blobWidth  = tables.blobIndexSize();
        final int paramWidth = tables.tableIndexSize( MetadataTables.TBL_PARAM );
        for( int i = 1; i <= count; i++ ) {
            final int rva       = buf.getInt();
            final int implFlags = buf.getShort() & 0xFFFF;
            final int flags     = buf.getShort() & 0xFFFF;
            final int nameIdx   = heaps.readIndex( strWidth );
            final int sig       = heaps.readIndex( blobWidth );
            final int param     = heaps.readRowIndex( paramWidth );
            final int nextRow   = buf.position();
            methodDefs.put( i, new TableRows.MethodDefRow( i, rva, implFlags, flags,
                heaps.readString( nameIdx ), sig, param ) );
            buf.position( nextRow );
        }
    }

    /**
     * Walk the CustomAttribute table and populate typeDefGuids for every TypeDef that has
     * a GuidAttribute (Windows.Foundation.Metadata.GuidAttribute) applied to it.
     *
     * MemberRefParent coded index tag 1 = TypeRef.
     * HasCustomAttribute coded index tag 3 = TypeDef.
     * CustomAttributeType coded index tag 3 = MemberRef.
     */
    private void loadCustomAttributes()
    {
        // Pre-build set of MemberRef row indices that are GuidAttribute constructors
        final Set<Integer> guidCtorMemberRefs = new HashSet<>();
        for( final TableRows.MemberRefRow mr : memberRefs.values() ) {
            if( !".ctor".equals( mr.name ) ) continue;
            final int tag      = mr.classCodedIndex & 0x7; // MemberRefParent: 3 tag bits
            final int rowIndex = mr.classCodedIndex >>> 3;
            if( tag == 1 ) {                               // TypeRef
                final TableRows.TypeRefRow tr = typeRefs.get( rowIndex );
                if( tr != null
                    && "GuidAttribute".equals( tr.typeName )
                    && "Windows.Foundation.Metadata".equals( tr.typeNamespace ) )
                    guidCtorMemberRefs.add( mr.rowIndex );
            }
        }

        final ByteBuffer buf         = meta.buffer();
        final int        count       = tables.rowCounts[MetadataTables.TBL_CUSTOM_ATTRIBUTE];
        buf.position( tables.tableOffset( MetadataTables.TBL_CUSTOM_ATTRIBUTE ) );
        final int parentWidth = tables.codedIndexSize( MetadataTables.CI_HAS_CUSTOM_ATTRIBUTE );
        final int typeWidth   = tables.codedIndexSize( MetadataTables.CI_CUSTOM_ATTRIBUTE_TYPE );
        final int blobWidth   = tables.blobIndexSize();
        for( int i = 1; i <= count; i++ ) {
            final int parentCoded = heaps.readIndex( parentWidth );
            final int typeCoded   = heaps.readIndex( typeWidth );
            final int valueBlob   = heaps.readIndex( blobWidth );
            final int nextRow     = buf.position(); // save before any heap access

            final int typeTag = typeCoded & 0x7;   // CustomAttributeType: 3 tag bits, MemberRef=3
            final int typeRow = typeCoded >>> 3;
            if( typeTag == 3 && guidCtorMemberRefs.contains( typeRow ) ) {
                final int parentTag = parentCoded & 0x1F; // HasCustomAttribute: 5 tag bits, TypeDef=3
                final int parentRow = parentCoded >>> 5;
                if( parentTag == 3 ) {
                    try {
                        typeDefGuids.put( parentRow, heaps.readGuidFromBlob( heaps.readBlob( valueBlob ) ) );
                    }
                    catch( final Exception ignored ) {}
                }
            }
            buf.position( nextRow ); // restore for next row
        }
    }

    /**
     * Build WinMDInterface objects from the loaded table data.
     *
     * Method range for TypeDef at row R: [methodListIndex(R) .. methodListIndex(R+1) - 1].
     * The last TypeDef's range ends at rowCounts[TBL_METHOD_DEF].
     * Row order within the range = vtable declaration order (ECMA-335).
     */
    private void buildInterfaces()
    {
        final int totalMethods = tables.rowCounts[MetadataTables.TBL_METHOD_DEF];

        for( final TableRows.TypeDefRow td : typeDefs.values() ) {
            final int methodStart = td.methodListIndex;
            final Map.Entry<Integer, Integer> next = allMethodLists.higherEntry( td.rowIndex );
            final int methodEnd = (next != null) ? next.getValue() - 1 : totalMethods;

            final List<String> methods = new ArrayList<>();
            for( int m = methodStart; m <= methodEnd; m++ ) {
                final TableRows.MethodDefRow md = methodDefs.get( m );
                if( md != null ) methods.add( md.name );
            }

            interfacesByName.put( td.fullName(),
                new WinMDInterface( td.typeNamespace, td.typeName,
                    typeDefGuids.get( td.rowIndex ), methods ) );
        }
    }
}
