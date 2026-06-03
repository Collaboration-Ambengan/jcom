# jcom

A Java toolkit for parsing ECMA-335 (CLI) metadata and consuming WinMD files,
enabling dynamic binding and interaction with COM and WinRT components at runtime.

## Packages

### `com/`
COM interop layer.

- **`COMRuntime`** — bootstraps the COM runtime environment; handles initialisation and teardown
- **`COMObject`** — base abstraction for COM objects; manages lifetime, vtable dispatch, and interface querying
- **`COMMethod`** — represents a single COM method; handles argument marshalling and native invocation
- **`IInspectable`** — Java-side projection of the WinRT `IInspectable` interface; entry point for WinRT object introspection

### `ecma335/`
Low-level ECMA-335 CLI metadata reader.

- **`CliMetadataReader`** — reads and validates CLI metadata headers; locates the metadata root and stream headers
- **`HeapReader`** — provides access to the `#Strings`, `#GUID`, `#Blob`, and `#US` heap streams
- **`MetadataTables`** — decodes the `#~` (compressed metadata) stream; exposes typed rows for all standard CLI tables
- **`TableRows`** — value types representing individual rows from CLI tables (TypeDef, MethodDef, Field, Param, etc.)

### `winmd/`
WinMD file reader and interface resolver.

- **`WinMDLocator`** — locates WinMD files on the system (Windows SDK paths, registry, side-by-side)
- **`WinMDReader`** — opens a WinMD file and drives `CliMetadataReader` to extract type and method metadata
- **`WinMDInterface`** — represents a resolved WinRT interface with its methods, parameters, and GUIDs

### `winrt/`
WinRT runtime binding layer.

- **`WinRTResolver`** — resolves WinRT class names to their factory and activation interfaces via `RoGetActivationFactory`
- **`WinRTProxy`** — dynamic proxy that intercepts Java interface method calls and dispatches them to the WinRT ABI
- **`WinRTException`** — wraps WinRT `HRESULT` error codes as typed Java exceptions with message lookup

## Usage as a submodule

This repo is designed to be used as a git submodule. Check it out into the
`gui_frontend/src/jcom` directory of the parent repo so that the package
structure resolves correctly:

```bash
# From the parent repo root
git submodule add git@github.com:Collaboration-Ambengan/jcom.git gui_frontend/src/jcom
git submodule update --init gui_frontend/src/jcom
```

After checkout the layout should be:

```
gui_frontend/src/jcom/
├── com/
│   ├── COMMethod.java
│   ├── COMObject.java
│   ├── COMRuntime.java
│   └── IInspectable.java
├── ecma335/
│   ├── CliMetadataReader.java
│   ├── HeapReader.java
│   ├── MetadataTables.java
│   └── TableRows.java
├── winmd/
│   ├── WinMDInterface.java
│   ├── WinMDLocator.java
│   └── WinMDReader.java
└── winrt/
    ├── WinRTException.java
    ├── WinRTProxy.java
    └── WinRTResolver.java
```

If you are using [mgit](https://github.com/blue-wind-25/mgit), the parent repo
manages this submodule automatically — `mgit pull`, `mgit commit`, and
`mgit push` all handle submodule ordering correctly.

## Additional files

- **`WinMDDump.java`** — standalone CLI utility; dumps human-readable metadata
  from a WinMD file to stdout. Run directly with `javac` and `java`, no
  build system required.
- **`CLAUDE.md`** — notes for AI coding agents working in this codebase.
- **`PROGRESS.md`** — implementation progress and known gaps.
- **`How-to-Obtain-WinMD.txt`** — instructions for locating WinMD files on
  Windows SDK installations.

## License

Apache License 2.0 — see `LICENSE_APACHEv2.txt`.
