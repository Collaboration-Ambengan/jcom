/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom.ecma335;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parses the PE (Portable Executable) container of a .winmd file
 * to locate the CLI metadata root, then hands off to MetadataTables.
 *
 * ECMA-335 6th edition, Partition II sections 24-25 describe the
 * on-disk format. A .winmd is a PE32/PE32+ file with:
 *
 *   DOS header (0x00)
 *     └─ e_lfanew → PE signature (0x50450000)
 *         └─ COFF header (20 bytes)
 *             └─ Optional header
 *                 └─ Data directories[14] = CLI header RVA+size
 *                     └─ IMAGE_COR20_HEADER
 *                         └─ MetaData RVA+size → metadata root
 *                             └─ #~ stream  → compressed tables
 *                             └─ #Strings heap
 *                             └─ #Blob heap
 *                             └─ #GUID heap
 */
public class CliMetadataReader {

    // PE magic numbers
    private static final int  PE_SIGNATURE       = 0x00004550;  // "PE\0\0"
    private static final int  CLI_DATA_DIRECTORY = 14;          // index in data directories
    private static final long METADATA_MAGIC     = 0x424A5342L; // "BSJB"

    // PE optional header magic
    private static final int PE32     = 0x010B;
    private static final int PE32plus = 0x020B;

    /** Raw file bytes — kept for RVA resolution and heap access. */
    private ByteBuffer buf;

    /** File offset of the start of the metadata root (after BSJB magic). */
    public int metadataRootOffset;

    /** File offset of the #~ (compressed tables) stream. */
    public int tablesStreamOffset;

    /** File offset + size of #Strings heap. */
    public int stringsHeapOffset;
    public int stringsHeapSize;

    /** File offset + size of #Blob heap. */
    public int blobHeapOffset;
    public int blobHeapSize;

    /** File offset + size of #GUID heap. */
    public int guidHeapOffset;
    public int guidHeapSize;

    /** Section headers needed for RVA→offset conversion. */
    private int[] sectionVAs;     // virtual addresses
    private int[] sectionOffsets; // file offsets
    private int[] sectionSizes;   // raw sizes
    private int   sectionCount;
    private int   optionalHeaderSize;

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Load and parse a .winmd file, populating all stream offset fields.
     * After this returns, pass this instance to MetadataTables for table parsing.
     *
     * @param winmdPath path to Windows.winmd or any .winmd file
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file is not a valid CLI image
     */
    public void load( final Path winmdPath ) throws IOException
    {
        buf = ByteBuffer.wrap( Files.readAllBytes( winmdPath ) ).order( ByteOrder.LITTLE_ENDIAN );

        final int peOffset        = readDosHeader();
        final int coffOffset      = readPESignature( peOffset );
        final int optHeaderOffset = readCoffHeader( coffOffset );
        final int dataDirsOffset  = readOptionalHeader( optHeaderOffset );
        final int cliHeaderRva    = readCliHeaderRva( dataDirsOffset );
        final int cliHeaderOffset = rvaToFileOffset( cliHeaderRva );
        final int metadataRva     = readCliHeader( cliHeaderOffset );
        metadataRootOffset        = rvaToFileOffset( metadataRva );
        readMetadataRoot( metadataRootOffset );
    }

    /** Access the underlying buffer for heap reads by MetadataTables/HeapReader. */
    public ByteBuffer buffer() { return buf; }

    // ── Private parsing steps ───────────────────────────────────────────────

    /**
     * Read DOS header, return e_lfanew (offset of PE signature).
     * DOS header starts at 0x00; e_lfanew is a 4-byte LE int at offset 0x3C.
     */
    private int readDosHeader()
    {
        buf.position( 0 );
        final int mz = buf.getShort() & 0xFFFF;
        if( mz != 0x5A4D )
            throw new IllegalArgumentException( "Not a PE file: invalid MZ magic 0x" + Integer.toHexString( mz ) );
        buf.position( 0x3C );
        return buf.getInt();
    }

    /**
     * Verify PE signature "PE\0\0" at peOffset, return offset of COFF header.
     */
    private int readPESignature( final int peOffset )
    {
        buf.position( peOffset );
        final int sig = buf.getInt();
        if( sig != PE_SIGNATURE )
            throw new IllegalArgumentException( "Not a PE file: invalid PE signature 0x" + Integer.toHexString( sig ) );
        return peOffset + 4;
    }

    /**
     * Read COFF header (20 bytes), extract section count and optional header size.
     * Returns offset of optional header (coffOffset + 20).
     *
     * COFF header layout:
     *   +0  uint16 Machine
     *   +2  uint16 NumberOfSections
     *   +4  uint32 TimeDateStamp
     *   +8  uint32 PointerToSymbolTable
     *   +12 uint32 NumberOfSymbols
     *   +16 uint16 SizeOfOptionalHeader
     *   +18 uint16 Characteristics
     */
    private int readCoffHeader( final int coffOffset )
    {
        buf.position( coffOffset );
        buf.getShort();                        // Machine
        sectionCount       = buf.getShort() & 0xFFFF;
        buf.getInt();                          // TimeDateStamp
        buf.getInt();                          // PointerToSymbolTable
        buf.getInt();                          // NumberOfSymbols
        optionalHeaderSize = buf.getShort() & 0xFFFF;
        buf.getShort();                        // Characteristics

        sectionVAs     = new int[sectionCount];
        sectionOffsets = new int[sectionCount];
        sectionSizes   = new int[sectionCount];
        return coffOffset + 20;
    }

    /**
     * Read optional header, detect PE32 vs PE32+, populate section arrays.
     * Returns offset of data directories array.
     *
     * Optional header Magic:
     *   0x010B = PE32  → data dirs at offset +96 from optional header start
     *   0x020B = PE32+ → data dirs at offset +112 from optional header start
     *
     * Section header layout (40 bytes each):
     *   +0  char[8] Name
     *   +8  uint32 VirtualSize
     *   +12 uint32 VirtualAddress
     *   +16 uint32 SizeOfRawData
     *   +20 uint32 PointerToRawData
     */
    private int readOptionalHeader( final int optHeaderOffset )
    {
        buf.position( optHeaderOffset );
        final int magic = buf.getShort() & 0xFFFF;
        final int dataDirsOffset;
        if( magic == PE32 )         dataDirsOffset = optHeaderOffset + 96;
        else if( magic == PE32plus ) dataDirsOffset = optHeaderOffset + 112;
        else throw new IllegalArgumentException( "Unknown optional header magic: 0x" + Integer.toHexString( magic ) );

        final int sectionsStart = optHeaderOffset + optionalHeaderSize;
        for( int i = 0; i < sectionCount; i++ ) {
            final int base = sectionsStart + i * 40;
            buf.position( base + 12 ); sectionVAs[i]     = buf.getInt(); // VirtualAddress
            buf.position( base + 16 ); sectionSizes[i]   = buf.getInt(); // SizeOfRawData
            buf.position( base + 20 ); sectionOffsets[i] = buf.getInt(); // PointerToRawData
        }
        return dataDirsOffset;
    }

    /** Read CLI header RVA from data directory entry 14 (8 bytes: uint32 RVA + uint32 Size). */
    private int readCliHeaderRva( final int dataDirsOffset )
    {
        buf.position( dataDirsOffset + CLI_DATA_DIRECTORY * 8 );
        return buf.getInt(); // RVA
    }

    /**
     * Read IMAGE_COR20_HEADER, return MetaData RVA.
     *
     * IMAGE_COR20_HEADER layout:
     *   +0  uint32 cb
     *   +4  uint16 MajorRuntimeVersion
     *   +6  uint16 MinorRuntimeVersion
     *   +8  uint32 MetaData.VirtualAddress  ← returned
     *   +12 uint32 MetaData.Size
     */
    private int readCliHeader( final int cliHeaderOffset )
    {
        buf.position( cliHeaderOffset + 8 );
        return buf.getInt(); // MetaData.VirtualAddress
    }

    /** Convert a Relative Virtual Address (RVA) to a file offset using the section table. */
    public int rvaToFileOffset( final int rva )
    {
        for( int i = 0; i < sectionCount; i++ ) {
            final int va = sectionVAs[i];
            final int sz = sectionSizes[i];
            if( Integer.compareUnsigned( rva, va ) >= 0 &&
                Integer.compareUnsigned( rva, va + sz ) < 0 )
                return sectionOffsets[i] + (rva - va);
        }
        throw new IllegalArgumentException( "RVA not in any section: 0x" + Integer.toHexString( rva ) );
    }

    /**
     * Parse metadata root at metaOffset.  Verifies BSJB magic, reads version string,
     * then reads stream headers and populates heap/table offset fields.
     *
     * Metadata root layout:
     *   +0  uint32 Signature (0x424A5342 "BSJB")
     *   +4  uint16 MajorVersion
     *   +6  uint16 MinorVersion
     *   +8  uint32 Reserved
     *   +12 uint32 Length (version string, padded to 4-byte boundary)
     *   +16 char[Length] VersionString
     *   +16+Length uint16 Flags
     *   +18+Length uint16 Streams
     *   then Streams × { uint32 Offset, uint32 Size, char[] Name (null-terminated, 4-byte padded) }
     */
    private void readMetadataRoot( final int metaOffset )
    {
        buf.position( metaOffset );
        final int magic = buf.getInt();
        if( Integer.toUnsignedLong( magic ) != METADATA_MAGIC )
            throw new IllegalArgumentException( "Invalid metadata magic: 0x" + Integer.toHexString( magic ) );

        buf.getShort();                            // MajorVersion
        buf.getShort();                            // MinorVersion
        buf.getInt();                              // Reserved
        final int versionLength = buf.getInt();    // padded to 4-byte boundary
        buf.position( buf.position() + versionLength );

        buf.getShort();                            // Flags
        final int streamCount = buf.getShort() & 0xFFFF;

        for( int i = 0; i < streamCount; i++ ) {
            final int streamOffset = buf.getInt();
            final int streamSize   = buf.getInt();

            // Read null-terminated name, padded to 4-byte boundary
            final int     nameStart = buf.position();
            final StringBuilder sb  = new StringBuilder();
            byte b;
            while( (b = buf.get()) != 0 ) sb.append( (char) b );
            final int nameLen = buf.position() - nameStart; // includes null byte
            buf.position( nameStart + ((nameLen + 3) & ~3) );

            switch( sb.toString() ) {
                case "#~"       -> tablesStreamOffset = metaOffset + streamOffset;
                case "#Strings" -> { stringsHeapOffset = metaOffset + streamOffset; stringsHeapSize = streamSize; }
                case "#Blob"    -> { blobHeapOffset    = metaOffset + streamOffset; blobHeapSize    = streamSize; }
                case "#GUID"    -> { guidHeapOffset    = metaOffset + streamOffset; guidHeapSize    = streamSize; }
            }
        }
    }
}
