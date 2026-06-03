/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom.ecma335;

/**
 * Plain data classes representing rows from the metadata tables
 * that WinMDReader needs.
 *
 * Row objects are created by WinMDReader while walking tables —
 * they're simple value holders, not parsed further here.
 *
 * All string fields are already resolved from the #Strings heap.
 * All index fields are 1-based per ECMA-335 convention (0 = null/none).
 */
public final class TableRows {

    private TableRows() {}

    // ── TypeDef row (table 0x02) ─────────────────────────────────────────────

    /**
     * One row from the TypeDef table.
     *
     * Represents a type defined in this assembly — for .winmd files
     * these are WinRT interface definitions like IGattCharacteristic.
     *
     * ECMA-335 §II.22.37 TypeDef columns:
     *   Flags          uint32    — TypeAttributes bitmask
     *   TypeName       string    — simple name (e.g. "IGattCharacteristic")
     *   TypeNamespace  string    — namespace  (e.g. "Windows.Devices.Bluetooth.GenericAttributeProfile")
     *   Extends        coded index TypeDefOrRef  — base type (IInspectable for WinRT interfaces)
     *   FieldList      table index → Field       — first field row (1-based)
     *   MethodList     table index → MethodDef   — first method row (1-based)
     *
     * TypeAttributes flags relevant to WinRT:
     *   0x00000020 = tdInterface     — this is an interface
     *   0x00004000 = tdWindowsRuntime — this is a WinRT type
     */
    public static final class TypeDefRow {
        public final int    rowIndex;        // 1-based row index in TypeDef table
        public final int    flags;
        public final String typeName;        // resolved from #Strings
        public final String typeNamespace;   // resolved from #Strings
        public final int    extendsCodedIndex;
        public final int    fieldListIndex;  // first Field row (1-based)
        public final int    methodListIndex; // first MethodDef row (1-based)

        public TypeDefRow( final int rowIndex, final int flags,
            final String typeName, final String typeNamespace,
            final int extendsCodedIndex, final int fieldListIndex, final int methodListIndex )
        {
            this.rowIndex          = rowIndex;
            this.flags             = flags;
            this.typeName          = typeName;
            this.typeNamespace     = typeNamespace;
            this.extendsCodedIndex = extendsCodedIndex;
            this.fieldListIndex    = fieldListIndex;
            this.methodListIndex   = methodListIndex;
        }

        /** True if this row represents a WinRT interface. */
        public boolean isInterface()      { return (flags & 0x00000020) != 0; }

        /** True if this row is tagged as a Windows Runtime type. */
        public boolean isWindowsRuntime() { return (flags & 0x00004000) != 0; }

        /** Fully qualified name: namespace.typeName */
        public String fullName()
        {
            return typeNamespace.isEmpty() ? typeName : typeNamespace + "." + typeName;
        }

        @Override public String toString()
        {
            return "TypeDef[" + rowIndex + "] " + fullName()
                   + (isInterface()      ? " (interface)" : "")
                   + (isWindowsRuntime() ? " [WinRT]"     : "");
        }
    }

    // ── MethodDef row (table 0x06) ───────────────────────────────────────────

    /**
     * One row from the MethodDef table.
     *
     * For WinRT interfaces, method order in this table determines
     * vtable slot order (offset +6 past IInspectable base slots).
     *
     * ECMA-335 §II.22.26 MethodDef columns:
     *   RVA         uint32   — 0 for abstract interface methods in .winmd
     *   ImplFlags   uint16   — MethodImplAttributes
     *   Flags       uint16   — MethodAttributes
     *   Name        string   — method name (e.g. "WriteValueAsync")
     *   Signature   blob     — method signature (param types etc.)
     *   ParamList   table index → Param  — first Param row (1-based)
     */
    public static final class MethodDefRow {
        public final int    rowIndex; // 1-based row index in MethodDef table
        public final int    rva;
        public final int    implFlags;
        public final int    flags;
        public final String name;     // resolved from #Strings
        public final int    signatureBlobIndex;
        public final int    paramListIndex;

        public MethodDefRow( final int rowIndex, final int rva,
            final int implFlags, final int flags, final String name,
            final int signatureBlobIndex, final int paramListIndex )
        {
            this.rowIndex           = rowIndex;
            this.rva                = rva;
            this.implFlags          = implFlags;
            this.flags              = flags;
            this.name               = name;
            this.signatureBlobIndex = signatureBlobIndex;
            this.paramListIndex     = paramListIndex;
        }

        @Override public String toString() { return "MethodDef[" + rowIndex + "] " + name; }
    }

    // ── CustomAttribute row (table 0x0C) ─────────────────────────────────────

    /**
     * One row from the CustomAttribute table.
     *
     * Used to find GuidAttribute on WinRT interfaces — that's where
     * the COM interface GUID lives.
     *
     * ECMA-335 §II.22.10 CustomAttribute columns:
     *   Parent  coded index HasCustomAttribute — the decorated type/method/etc.
     *   Type    coded index CustomAttributeType — the attribute constructor
     *   Value   blob — the attribute arguments (contains raw GUID bytes for GuidAttribute)
     */
    public static final class CustomAttributeRow {
        public final int rowIndex;
        public final int parentCodedIndex; // HasCustomAttribute coded index
        public final int typeCodedIndex;   // CustomAttributeType coded index
        public final int valueBlobIndex;   // index into #Blob heap

        public CustomAttributeRow( final int rowIndex, final int parentCodedIndex,
            final int typeCodedIndex, final int valueBlobIndex )
        {
            this.rowIndex         = rowIndex;
            this.parentCodedIndex = parentCodedIndex;
            this.typeCodedIndex   = typeCodedIndex;
            this.valueBlobIndex   = valueBlobIndex;
        }

        @Override public String toString()
        {
            return "CustomAttribute[" + rowIndex + "]"
                   + " parent=" + parentCodedIndex
                   + " type="   + typeCodedIndex;
        }
    }

    // ── MemberRef row (table 0x0A) ───────────────────────────────────────────

    /**
     * One row from the MemberRef table.
     *
     * Used to resolve the CustomAttributeType coded index when it
     * points to a MemberRef (which is the case for GuidAttribute
     * defined in Windows.Foundation.Metadata assembly).
     *
     * ECMA-335 §II.22.25 MemberRef columns:
     *   Class      coded index MemberRefParent — the declaring type
     *   Name       string — member name (e.g. ".ctor" for constructors)
     *   Signature  blob   — method or field signature
     */
    public static final class MemberRefRow {
        public final int    rowIndex;
        public final int    classCodedIndex; // MemberRefParent coded index
        public final String name;            // resolved from #Strings
        public final int    signatureBlobIndex;

        public MemberRefRow( final int rowIndex, final int classCodedIndex,
            final String name, final int signatureBlobIndex )
        {
            this.rowIndex           = rowIndex;
            this.classCodedIndex    = classCodedIndex;
            this.name               = name;
            this.signatureBlobIndex = signatureBlobIndex;
        }

        @Override public String toString() { return "MemberRef[" + rowIndex + "] " + name; }
    }

    // ── TypeRef row (table 0x01) ─────────────────────────────────────────────

    /**
     * One row from the TypeRef table.
     *
     * Used to resolve the declaring type of a MemberRef — needed
     * to confirm a MemberRef belongs to GuidAttribute.
     *
     * ECMA-335 §II.22.38 TypeRef columns:
     *   ResolutionScope  coded index — where to look for this type
     *   TypeName         string
     *   TypeNamespace    string
     */
    public static final class TypeRefRow {
        public final int    rowIndex;
        public final int    resolutionScopeCodedIndex;
        public final String typeName;
        public final String typeNamespace;

        public TypeRefRow( final int rowIndex, final int resolutionScopeCodedIndex,
            final String typeName, final String typeNamespace )
        {
            this.rowIndex                  = rowIndex;
            this.resolutionScopeCodedIndex = resolutionScopeCodedIndex;
            this.typeName                  = typeName;
            this.typeNamespace             = typeNamespace;
        }

        public String fullName()
        {
            return typeNamespace.isEmpty() ? typeName : typeNamespace + "." + typeName;
        }

        @Override public String toString() { return "TypeRef[" + rowIndex + "] " + fullName(); }
    }
}
