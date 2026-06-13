> These instructions are for interactive local development only.
> If you are running as a CI bot, GitHub Action, or any automated
> agent, ignore the tooling sections and follow your workflow's own
> rules. Architecture and constraint notes still apply.

# jcom Developer Guide

## Project structure

```
jcom/
├── ecma335/   — ECMA-335 CLI metadata reader (CliMetadataReader, MetadataTables, HeapReader, TableRows)
├── winmd/     — WinMD file reader and interface resolver (WinMDLocator, WinMDReader, WinMDInterface)
├── com/       — COM interop layer (COMRuntime, COMObject, COMMethod, IInspectable)
├── winrt/     — WinRT runtime binding (WinRTResolver, WinRTProxy, WinRTException)
└── WinMDDump.java — standalone CLI test utility; dumps WinMD metadata to stdout
```

## Platform constraints

- **ecma335/ and winmd/** compile and run on Linux. WinMDDump is the primary test target.
- **com/ and winrt/** compile on Linux but require Windows + `combase.dll` at runtime.
  Runtime testing for these packages requires a Windows machine.

## BLE subscribe/unsubscribe — platform difference

BlueZ (Linux):
  StartNotify()  → tells kernel BT stack to subscribe
  StopNotify()   → unsubscribe

WinRT (Windows):
  WriteClientCharacteristicConfigurationDescriptorAsync(NOTIFY=1)  → subscribe
  WriteClientCharacteristicConfigurationDescriptorAsync(NOTIFY=0)  → unsubscribe
  add_ValueChanged(handler)  → register callback

## Test file

`/tmp/Windows.winmd` (~7.4 MB, full SDK file containing BLE interfaces).
See `How-to-Obtain-WinMD.txt` for how to obtain it.

## Java path

/opt/openjdk-25_linux-x64_bin/jdk-25/bin/java

## How to compile and test

This repo is used as a submodule. The build command depends on the parent
project's layout on the current machine and is not stored here.

If you need to compile, ask the user for the build command. Do not assume or guess it.

## Known git CLI limitation

The installed git does not support `git -C <path>`. Use `(cd <path> && git ...)` instead.

## Development discipline

- One method at a time
- Compile after each method
- Commit after each file is complete
- Do not rewrite entire files in one shot
