/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom.ecma335;

import java.nio.ByteBuffer;

/**
 * Parses the #~ (compressed metadata tables) stream header and
 * provides row counts and column widths for each table.
 *
 * ECMA-335 Partition II §24.2.6 — Metadata table stream header:
 *   +0  uint32 Reserved (0)
 *   +4  uint8  MajorVersion
 *   +5  uint8  MinorVersion
 *   +6  uint8  HeapSizes      ← bit flags: 0x01=wide strings, 0x02=wide guids, 0x04=wide blobs
 *   +7  uint8  Reserved (1)
 *   +8  uint64 Valid          ← bitmask of which tables are present
 *   +16 uint64 Sorted         ← bitmask of which tables are sorted
 *   +24 uint32[popcount(Valid)] rows — one per present table in bit order
 *   then table data follows
 *
 * Table indices (ECMA-335 Table II.22):
 *   0x00 Module           0x01 TypeRef        0x02 TypeDef
 *   0x04 Field            0x06 MethodDef      0x08 Param
 *   0x09 InterfaceImpl    0x0A MemberRef      0x0B Constant
 *   0x0C CustomAttribute  0x0D FieldMarshal   0x0F DeclSecurity
 *   0x11 ClassLayout      0x12 FieldLayout    0x14 StandAloneSig
 *   0x15 EventMap         0x17 Event          0x18 PropertyMap
 *   0x1A Property         0x1B MethodSemantics 0x1C MethodImpl
 *   0x1D ModuleRef        0x1E TypeSpec       0x1F ImplMap
 *   0x20 FieldRva         0x23 Assembly       0x26 AssemblyRef
 *   0x29 File             0x2A ExportedType   0x2B ManifestResource
 *   0x2C NestedClass      0x2D GenericParam   0x2E MethodSpec
 *   0x2F GenericParamConstraint
 */
public class MetadataTables {

    // ── Table index constants ────────────────────────────────────────────────
    public static final int TBL_MODULE                   = 0x00;
    public static final int TBL_TYPE_REF                 = 0x01;
    public static final int TBL_TYPE_DEF                 = 0x02;
    public static final int TBL_FIELD                    = 0x04;
    public static final int TBL_METHOD_DEF               = 0x06;
    public static final int TBL_PARAM                    = 0x08;
    public static final int TBL_INTERFACE_IMPL           = 0x09;
    public static final int TBL_MEMBER_REF               = 0x0A;
    public static final int TBL_CONSTANT                 = 0x0B;
    public static final int TBL_CUSTOM_ATTRIBUTE         = 0x0C;
    public static final int TBL_FIELD_MARSHAL            = 0x0D;
    public static final int TBL_DECL_SECURITY            = 0x0F;
    public static final int TBL_CLASS_LAYOUT             = 0x11;
    public static final int TBL_FIELD_LAYOUT             = 0x12;
    public static final int TBL_STAND_ALONE_SIG          = 0x14;
    public static final int TBL_EVENT_MAP                = 0x15;
    public static final int TBL_EVENT                    = 0x17;
    public static final int TBL_PROPERTY_MAP             = 0x18;
    public static final int TBL_PROPERTY                 = 0x1A;
    public static final int TBL_METHOD_SEMANTICS         = 0x1B;
    public static final int TBL_METHOD_IMPL              = 0x1C;
    public static final int TBL_MODULE_REF               = 0x1D;
    public static final int TBL_TYPE_SPEC                = 0x1E;
    public static final int TBL_IMPL_MAP                 = 0x1F;
    public static final int TBL_FIELD_RVA                = 0x20;
    public static final int TBL_ASSEMBLY                 = 0x23;
    public static final int TBL_ASSEMBLY_REF             = 0x26;
    public static final int TBL_FILE                     = 0x29;
    public static final int TBL_EXPORTED_TYPE            = 0x2A;
    public static final int TBL_MANIFEST_RESOURCE        = 0x2B;
    public static final int TBL_NESTED_CLASS             = 0x2C;
    public static final int TBL_GENERIC_PARAM            = 0x2D;
    public static final int TBL_METHOD_SPEC              = 0x2E;
    public static final int TBL_GENERIC_PARAM_CONSTRAINT = 0x2F;
    public static final int MAX_TABLES                   = 0x30;

    // ── Coded index type constants ───────────────────────────────────────────
    public static final int CI_TYPE_DEF_OR_REF       = 0;
    public static final int CI_HAS_CONSTANT          = 1;
    public static final int CI_HAS_CUSTOM_ATTRIBUTE  = 2;
    public static final int CI_HAS_FIELD_MARSHAL     = 3;
    public static final int CI_HAS_DECL_SECURITY     = 4;
    public static final int CI_MEMBER_REF_PARENT     = 5;
    public static final int CI_HAS_SEMANTICS         = 6;
    public static final int CI_METHOD_DEF_OR_REF     = 7;
    public static final int CI_MEMBER_FORWARDED      = 8;
    public static final int CI_IMPLEMENTATION        = 9;
    public static final int CI_CUSTOM_ATTRIBUTE_TYPE = 10;
    public static final int CI_RESOLUTION_SCOPE      = 11;
    public static final int CI_TYPE_OR_METHOD_DEF    = 12;

    // ── HeapSizes flag bits ─────────────────────────────────────────────────
    private static final int HEAP_STRINGS_WIDE = 0x01;
    private static final int HEAP_GUIDS_WIDE   = 0x02;
    private static final int HEAP_BLOBS_WIDE   = 0x04;

    // ── State ────────────────────────────────────────────────────────────────
    private final CliMetadataReader meta;
    private final ByteBuffer        buf;

    /** Row counts for each table (0 if table not present). */
    public final int[] rowCounts = new int[MAX_TABLES];

    /** Whether string / GUID / blob heap indices are 4 bytes (vs 2). */
    public boolean wideStrings;
    public boolean wideGuids;
    public boolean wideBlobs;

    /** File offset where table row data starts (after the header + row counts). */
    public int tableDataOffset;

    private final int[] tableOffsets       = new int[MAX_TABLES];
    private       boolean tableOffsetsCached = false;

    public MetadataTables( final CliMetadataReader meta )
    {
        this.meta = meta;
        this.buf  = meta.buffer();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Parse the #~ stream header starting at meta.tablesStreamOffset.
     * Populates rowCounts[], wideStrings/Guids/Blobs, and tableDataOffset.
     */
    public void parseHeader()
    {
        buf.position( meta.tablesStreamOffset );
        buf.getInt(); // Reserved
        buf.get();    // MajorVersion
        buf.get();    // MinorVersion
        final int heapSizes = buf.get() & 0xFF;
        buf.get();    // Reserved (1)

        wideStrings = (heapSizes & HEAP_STRINGS_WIDE) != 0;
        wideGuids   = (heapSizes & HEAP_GUIDS_WIDE)   != 0;
        wideBlobs   = (heapSizes & HEAP_BLOBS_WIDE)   != 0;

        final long valid = buf.getLong(); // bitmask of present tables
        buf.getLong();                    // Sorted — skip

        for( int i = 0; i < 64; i++ ) {
            if( (valid & (1L << i)) != 0 ) {
                if( i < MAX_TABLES ) rowCounts[i] = buf.getInt();
                else                 buf.getInt(); // skip unknown table count
            }
        }
        tableDataOffset = buf.position();
    }

    /**
     * Return the file offset of the first row of the given table.
     * Results are cached on first call.
     */
    public int tableOffset( final int tableIndex )
    {
        if( !tableOffsetsCached ) {
            int offset = tableDataOffset;
            for( int i = 0; i < MAX_TABLES; i++ ) {
                tableOffsets[i] = offset;
                if( rowCounts[i] > 0 ) offset += rowCounts[i] * rowSize( i );
            }
            tableOffsetsCached = true;
        }
        return tableOffsets[tableIndex];
    }

    /**
     * Return size in bytes of one row for the given table.
     * Column widths depend on heap index sizes, table index sizes, and coded index sizes
     * per ECMA-335 Partition II §22.
     */
    public int rowSize( final int tableIndex )
    {
        final int s = stringsIndexSize(), g = guidIndexSize(), b = blobIndexSize();
        switch( tableIndex ) {
            case TBL_MODULE:            return 2 + s + 3 * g;          // §II.22.30
            case TBL_TYPE_REF:          return codedIndexSize( CI_RESOLUTION_SCOPE ) + 2 * s; // §II.22.38

            // ENC "ptr" tables — single index into the target table
            case 0x03: return tableIndexSize( TBL_FIELD );      // FieldPtr
            case 0x05: return tableIndexSize( TBL_METHOD_DEF ); // MethodPtr
            case 0x07: return tableIndexSize( TBL_PARAM );      // ParamPtr
            case 0x16: return tableIndexSize( TBL_EVENT );      // EventPtr
            case 0x19: return tableIndexSize( TBL_PROPERTY );   // PropertyPtr

            case TBL_TYPE_DEF:          // §II.22.37
                return 4 + 2 * s + codedIndexSize( CI_TYPE_DEF_OR_REF )
                       + tableIndexSize( TBL_FIELD ) + tableIndexSize( TBL_METHOD_DEF );
            case TBL_FIELD:             return 2 + s + b;                                 // §II.22.15
            case TBL_METHOD_DEF:        return 8 + s + b + tableIndexSize( TBL_PARAM );   // §II.22.26
            case TBL_PARAM:             return 4 + s;                                     // §II.22.33
            case TBL_INTERFACE_IMPL:    return tableIndexSize( TBL_TYPE_DEF ) + codedIndexSize( CI_TYPE_DEF_OR_REF );   // §II.22.23
            case TBL_MEMBER_REF:        return codedIndexSize( CI_MEMBER_REF_PARENT ) + s + b;                          // §II.22.25
            case TBL_CONSTANT:          return 2 + codedIndexSize( CI_HAS_CONSTANT ) + b;                               // §II.22.9
            case TBL_CUSTOM_ATTRIBUTE:  return codedIndexSize( CI_HAS_CUSTOM_ATTRIBUTE ) + codedIndexSize( CI_CUSTOM_ATTRIBUTE_TYPE ) + b; // §II.22.10
            case TBL_FIELD_MARSHAL:     return codedIndexSize( CI_HAS_FIELD_MARSHAL ) + b;                              // §II.22.17
            case TBL_DECL_SECURITY:     return 2 + codedIndexSize( CI_HAS_DECL_SECURITY ) + b;                          // §II.22.11
            case TBL_CLASS_LAYOUT:      return 6 + tableIndexSize( TBL_TYPE_DEF );                                      // §II.22.8
            case TBL_FIELD_LAYOUT:      return 4 + tableIndexSize( TBL_FIELD );                                         // §II.22.16
            case TBL_STAND_ALONE_SIG:   return b;                                                                       // §II.22.36
            case TBL_EVENT_MAP:         return tableIndexSize( TBL_TYPE_DEF ) + tableIndexSize( TBL_EVENT );             // §II.22.12
            case TBL_EVENT:             return 2 + s + codedIndexSize( CI_TYPE_DEF_OR_REF );                             // §II.22.13
            case TBL_PROPERTY_MAP:      return tableIndexSize( TBL_TYPE_DEF ) + tableIndexSize( TBL_PROPERTY );          // §II.22.35
            case TBL_PROPERTY:          return 2 + s + b;                                                               // §II.22.34
            case TBL_METHOD_SEMANTICS:  return 2 + tableIndexSize( TBL_METHOD_DEF ) + codedIndexSize( CI_HAS_SEMANTICS );// §II.22.28
            case TBL_METHOD_IMPL:       return tableIndexSize( TBL_TYPE_DEF ) + 2 * codedIndexSize( CI_METHOD_DEF_OR_REF ); // §II.22.27
            case TBL_MODULE_REF:        return s;                                                                        // §II.22.31
            case TBL_TYPE_SPEC:         return b;                                                                        // §II.22.39
            case TBL_IMPL_MAP:          return 2 + codedIndexSize( CI_MEMBER_FORWARDED ) + s + tableIndexSize( TBL_MODULE_REF ); // §II.22.22
            case TBL_FIELD_RVA:         return 4 + tableIndexSize( TBL_FIELD );                                         // §II.22.18
            case TBL_ASSEMBLY:          return 16 + b + 2 * s;                                                          // §II.22.2
            case TBL_ASSEMBLY_REF:      return 12 + 2 * b + 2 * s;                                                     // §II.22.5
            case TBL_FILE:              return 4 + s + b;                                                               // §II.22.19
            case TBL_EXPORTED_TYPE:     return 8 + 2 * s + codedIndexSize( CI_IMPLEMENTATION );                         // §II.22.14
            case TBL_MANIFEST_RESOURCE: return 8 + s + codedIndexSize( CI_IMPLEMENTATION );                             // §II.22.24
            case TBL_NESTED_CLASS:      return 2 * tableIndexSize( TBL_TYPE_DEF );                                      // §II.22.32
            case TBL_GENERIC_PARAM:     return 4 + codedIndexSize( CI_TYPE_OR_METHOD_DEF ) + s;                         // §II.22.20
            case TBL_METHOD_SPEC:       return codedIndexSize( CI_METHOD_DEF_OR_REF ) + b;                              // §II.22.29
            case TBL_GENERIC_PARAM_CONSTRAINT: return tableIndexSize( TBL_GENERIC_PARAM ) + codedIndexSize( CI_TYPE_DEF_OR_REF ); // §II.22.21
            default:
                throw new UnsupportedOperationException( "rowSize not implemented for table 0x"
                    + Integer.toHexString( tableIndex ) );
        }
    }

    // ── Heap index size helpers ──────────────────────────────────────────────

    /** Size of a string heap index in bytes (2 or 4). */
    public int stringsIndexSize() { return wideStrings ? 4 : 2; }

    /** Size of a GUID heap index in bytes (2 or 4). */
    public int guidIndexSize()    { return wideGuids   ? 4 : 2; }

    /** Size of a blob heap index in bytes (2 or 4). */
    public int blobIndexSize()    { return wideBlobs   ? 4 : 2; }

    /**
     * Size of a simple table index for the given table (2 or 4 bytes).
     * Uses 4 bytes only when the table has more than 65535 rows.
     */
    public int tableIndexSize( final int tableIndex )
    {
        return rowCounts[tableIndex] > 0xFFFF ? 4 : 2;
    }

    /**
     * Size of a coded index in bytes.
     * ECMA-335 §24.2.6: coded indices pack a table tag into the low bits.
     * Rule: if max_rows_in_referenced_tables &lt; (1 &lt;&lt; (16 - tagBits)) → 2 bytes, else 4.
     */
    public int codedIndexSize( final int codedIndexType )
    {
        final int   tagBits;
        final int[] tables;
        switch( codedIndexType ) {
            case CI_TYPE_DEF_OR_REF:
                tagBits = 2; tables = new int[]{ TBL_TYPE_DEF, TBL_TYPE_REF, TBL_TYPE_SPEC }; break;
            case CI_HAS_CONSTANT:
                tagBits = 2; tables = new int[]{ TBL_FIELD, TBL_PARAM, TBL_PROPERTY }; break;
            case CI_HAS_CUSTOM_ATTRIBUTE:
                tagBits = 5; tables = new int[]{
                    TBL_METHOD_DEF, TBL_FIELD, TBL_TYPE_REF, TBL_TYPE_DEF,
                    TBL_PARAM, TBL_INTERFACE_IMPL, TBL_MEMBER_REF, TBL_MODULE,
                    TBL_DECL_SECURITY, TBL_PROPERTY, TBL_EVENT, TBL_STAND_ALONE_SIG,
                    TBL_MODULE_REF, TBL_TYPE_SPEC, TBL_ASSEMBLY, TBL_ASSEMBLY_REF,
                    TBL_FILE, TBL_EXPORTED_TYPE, TBL_MANIFEST_RESOURCE,
                    TBL_GENERIC_PARAM, TBL_GENERIC_PARAM_CONSTRAINT, TBL_METHOD_SPEC
                }; break;
            case CI_HAS_FIELD_MARSHAL:
                tagBits = 1; tables = new int[]{ TBL_FIELD, TBL_PARAM }; break;
            case CI_HAS_DECL_SECURITY:
                tagBits = 2; tables = new int[]{ TBL_TYPE_DEF, TBL_METHOD_DEF, TBL_ASSEMBLY }; break;
            case CI_MEMBER_REF_PARENT:
                tagBits = 3; tables = new int[]{ TBL_TYPE_DEF, TBL_TYPE_REF, TBL_MODULE_REF, TBL_METHOD_DEF, TBL_TYPE_SPEC }; break;
            case CI_HAS_SEMANTICS:
                tagBits = 1; tables = new int[]{ TBL_EVENT, TBL_PROPERTY }; break;
            case CI_METHOD_DEF_OR_REF:
                tagBits = 1; tables = new int[]{ TBL_METHOD_DEF, TBL_MEMBER_REF }; break;
            case CI_MEMBER_FORWARDED:
                tagBits = 1; tables = new int[]{ TBL_FIELD, TBL_METHOD_DEF }; break;
            case CI_IMPLEMENTATION:
                tagBits = 2; tables = new int[]{ TBL_FILE, TBL_ASSEMBLY_REF, TBL_EXPORTED_TYPE }; break;
            case CI_CUSTOM_ATTRIBUTE_TYPE: // tags 0 and 1 are unused; 2=MethodDef, 3=MemberRef
                tagBits = 3; tables = new int[]{ TBL_METHOD_DEF, TBL_MEMBER_REF }; break;
            case CI_RESOLUTION_SCOPE:
                tagBits = 2; tables = new int[]{ TBL_MODULE, TBL_MODULE_REF, TBL_ASSEMBLY_REF, TBL_TYPE_REF }; break;
            case CI_TYPE_OR_METHOD_DEF:
                tagBits = 1; tables = new int[]{ TBL_TYPE_DEF, TBL_METHOD_DEF }; break;
            default:
                throw new IllegalArgumentException( "Unknown coded index type: " + codedIndexType );
        }
        int maxRows = 0;
        for( final int t : tables )
            if( t >= 0 && t < MAX_TABLES ) maxRows = Math.max( maxRows, rowCounts[t] );
        return maxRows < (1 << (16 - tagBits)) ? 2 : 4;
    }
}
