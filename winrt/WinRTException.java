/*
 * Copyright (C) 2026 Collaboration-Ambengan
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/Collaboration-Ambengan
 *
 * Created with a lot of assistance from Claude Sonnet 4.6.
 */

package jcom.winrt;

/**
 * Thrown when a COM/WinRT method returns a failed HRESULT.
 *
 * HRESULT encoding (32 bits):
 *   bit 31:     Severity   (0=success, 1=failure)
 *   bit 30:     Reserved
 *   bit 29:     Customer   (0=Microsoft, 1=customer)
 *   bits 16-28: Facility   (which subsystem)
 *   bits 0-15:  Code       (specific error within facility)
 *
 * Common facility codes:
 *   0x000 FACILITY_NULL
 *   0x001 FACILITY_RPC
 *   0x002 FACILITY_DISPATCH (IDispatch errors)
 *   0x004 FACILITY_ITF      (interface-specific errors)
 *   0x007 FACILITY_WIN32    (Win32 error codes wrapped in HRESULT)
 *   0x00A FACILITY_SECURITY
 */
public class WinRTException extends RuntimeException {

    private final int hresult;

    public WinRTException( final int hresult )
    {
        super( formatMessage( hresult ) );
        this.hresult = hresult;
    }

    public WinRTException( final int hresult, final String context )
    {
        super( context + ": " + formatMessage( hresult ) );
        this.hresult = hresult;
    }

    /** The raw HRESULT value. */
    public int     hresult()   { return hresult; }

    /** True if this is a failure HRESULT (bit 31 set). */
    public boolean isFailure() { return (hresult & 0x80000000) != 0; }

    /** The facility code (bits 16-26). */
    public int     facility()  { return (hresult >> 16) & 0x07FF; }

    /** The error code (bits 0-15). */
    public int     code()      { return hresult & 0xFFFF; }

    // ── Known HRESULT values ─────────────────────────────────────────────────

    public static final int S_OK               = 0x00000000;
    public static final int S_FALSE            = 0x00000001;
    public static final int E_NOTIMPL          = 0x80004001;
    public static final int E_NOINTERFACE      = 0x80004002;
    public static final int E_POINTER          = 0x80004003;
    public static final int E_ABORT            = 0x80004004;
    public static final int E_FAIL             = 0x80004005;
    public static final int E_ACCESSDENIED     = 0x80070005;
    public static final int E_OUTOFMEMORY      = 0x8007000E;
    public static final int E_INVALIDARG       = 0x80070057;
    public static final int CLASS_NOT_REG      = 0x80040154;
    public static final int RPC_E_CHANGED_MODE = 0x80010106; // CoInitialize threading model conflict

    // ── Formatting ───────────────────────────────────────────────────────────

    private static String formatMessage( final int hresult )
    {
        return switch( hresult ) {
            case S_OK               -> "S_OK (0x00000000) — success";
            case S_FALSE            -> "S_FALSE (0x00000001) — success with note";
            case E_NOTIMPL          -> "E_NOTIMPL (0x80004001) — not implemented";
            case E_NOINTERFACE      -> "E_NOINTERFACE (0x80004002) — interface not supported";
            case E_POINTER          -> "E_POINTER (0x80004003) — invalid pointer";
            case E_ABORT            -> "E_ABORT (0x80004004) — operation aborted";
            case E_FAIL             -> "E_FAIL (0x80004005) — unspecified failure";
            case E_ACCESSDENIED     -> "E_ACCESSDENIED (0x80070005) — access denied";
            case E_INVALIDARG       -> "E_INVALIDARG (0x80070057) — invalid argument";
            case E_OUTOFMEMORY      -> "E_OUTOFMEMORY (0x8007000E) — out of memory";
            case CLASS_NOT_REG      -> "REGDB_E_CLASSNOTREG (0x80040154) — class not registered";
            case RPC_E_CHANGED_MODE -> "RPC_E_CHANGED_MODE (0x80010106) — CoInitialize threading model conflict";
            default -> String.format( "HRESULT 0x%08X (facility=0x%03X code=0x%04X)",
                hresult, (hresult >> 16) & 0x07FF, hresult & 0xFFFF );
        };
    }
}
