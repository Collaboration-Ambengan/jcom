/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom.ecma335;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Reads values from the three ECMA-335 metadata heaps:
 *   #Strings — null-terminated UTF-8 strings (type/method names)
 *   #Blob    — length-prefixed byte sequences (signatures, attribute values)
 *   #GUID    — 16-byte GUIDs packed sequentially (1-based index)
 *
 * Heap indices come from table rows read by MetadataTables.
 * Index width (2 or 4 bytes) is determined by HeapSizes flags in MetadataTables.
 */
public class HeapReader {

    private final ByteBuffer buf;
    private final int        stringsOffset;
    private final int        blobOffset;
    private final int        guidOffset;

    public HeapReader( final CliMetadataReader meta )
    {
        this.buf           = meta.buffer();
        this.stringsOffset = meta.stringsHeapOffset;
        this.blobOffset    = meta.blobHeapOffset;
        this.guidOffset    = meta.guidHeapOffset;
    }

    // ── String heap ──────────────────────────────────────────────────────────

    /**
     * Read a null-terminated UTF-8 string from the #Strings heap.
     *
     * @param index offset within the #Strings heap (0 = empty string "")
     * @return the string value, never null
     */
    public String readString( final int index )
    {
        if( index == 0 ) return "";
        buf.position( stringsOffset + index );
        final int start = buf.position();
        while( buf.get() != 0 ) {}
        final int len = buf.position() - start - 1;
        return new String( buf.array(), start, len, StandardCharsets.UTF_8 );
    }

    // ── Blob heap ────────────────────────────────────────────────────────────

    /**
     * Read raw blob bytes from the #Blob heap.
     * Blobs are prefixed with a compressed uint length (1, 2, or 4 bytes).
     *
     * @param index offset within the #Blob heap
     * @return the blob bytes (length prefix excluded), never null
     */
    public byte[] readBlob( final int index )
    {
        if( index == 0 ) return new byte[0];
        buf.position( blobOffset + index );
        final int    len  = readCompressedUInt();
        final byte[] data = new byte[len];
        buf.get( data );
        return data;
    }

    // ── GUID heap ────────────────────────────────────────────────────────────

    /**
     * Read a GUID from the #GUID heap.
     * GUIDs are stored as 16 contiguous bytes, 1-based index.
     *
     * GUID byte layout in .winmd (matches Windows GUID struct):
     *   bytes 0-3:  Data1 (uint32 LE)
     *   bytes 4-5:  Data2 (uint16 LE)
     *   bytes 6-7:  Data3 (uint16 LE)
     *   bytes 8-15: Data4 (8 bytes, big-endian)
     *
     * @param index 1-based GUID index (0 = null GUID)
     * @return UUID, or null if index == 0
     */
    public UUID readGuid( final int index )
    {
        if( index == 0 ) return null;
        buf.position( guidOffset + (index - 1) * 16 );
        final long   data1    = buf.getInt()   & 0xFFFFFFFFL;
        final long   data2    = buf.getShort() & 0xFFFFL;
        final long   data3    = buf.getShort() & 0xFFFFL;
        final long   mostSig  = (data1 << 32) | (data2 << 16) | data3;
        final byte[] data4    = new byte[8];
        buf.get( data4 );
        final long   leastSig = ByteBuffer.wrap( data4 ).getLong();
        return new UUID( mostSig, leastSig );
    }

    // ── CustomAttribute blob parsing ─────────────────────────────────────────

    /**
     * Extract a UUID from a GuidAttribute CustomAttribute blob.
     *
     * GuidAttribute blob format (ECMA-335 §II.23.3):
     *   uint16   prolog    = 0x0001
     *   byte[16] guidBytes (little-endian GUID, same layout as #GUID heap)
     *   uint16   namedArgs = 0x0000
     *
     * @param blobBytes raw blob bytes from readBlob()
     * @return the UUID encoded in the blob
     */
    public UUID readGuidFromBlob( final byte[] blobBytes )
    {
        // prolog: 0x01 0x00 (little-endian 0x0001)
        if( blobBytes.length < 20
            || (blobBytes[0] & 0xFF) != 0x01
            || (blobBytes[1] & 0xFF) != 0x00 )
            throw new IllegalArgumentException( "Not a GuidAttribute blob (bad prolog)" );

        final ByteBuffer b        = ByteBuffer.wrap( blobBytes, 2, 16 ).order( ByteOrder.LITTLE_ENDIAN );
        final long       data1    = b.getInt()   & 0xFFFFFFFFL;
        final long       data2    = b.getShort() & 0xFFFFL;
        final long       data3    = b.getShort() & 0xFFFFL;
        final long       mostSig  = (data1 << 32) | (data2 << 16) | data3;
        final byte[]     data4    = new byte[8];
        b.get( data4 );
        final long       leastSig = ByteBuffer.wrap( data4 ).getLong();
        return new UUID( mostSig, leastSig );
    }

    // ── Compressed uint ──────────────────────────────────────────────────────

    /**
     * Read an ECMA-335 compressed unsigned integer from the buffer
     * at the current position, advancing the position past it.
     *
     * Encoding (§II.23.2):
     *   if (byte0 & 0x80) == 0:    1 byte,  value = byte0
     *   if (byte0 & 0xC0) == 0x80: 2 bytes, value = ((byte0 & 0x3F) << 8) | byte1
     *   if (byte0 & 0xE0) == 0xC0: 4 bytes, value = ((byte0 & 0x1F) << 24) | ...
     */
    public int readCompressedUInt()
    {
        final int b0 = buf.get() & 0xFF;
        if( (b0 & 0x80) == 0    ) return b0;
        if( (b0 & 0xC0) == 0x80 ) return ((b0 & 0x3F) << 8) | (buf.get() & 0xFF);
        // 4-byte form: high bits 110
        final int b1 = buf.get() & 0xFF;
        final int b2 = buf.get() & 0xFF;
        final int b3 = buf.get() & 0xFF;
        return ((b0 & 0x1F) << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    // ── Buffer index reader helpers ──────────────────────────────────────────

    /** Read a heap index of the given width (2 or 4 bytes) from buf, advancing position. */
    public int readIndex( final int width )
    {
        return width == 2 ? buf.getShort() & 0xFFFF : buf.getInt();
    }

    /** Read a table row index of given width from buf at current position. Row indices are 1-based. */
    public int readRowIndex( final int width ) { return readIndex( width ); }
}
