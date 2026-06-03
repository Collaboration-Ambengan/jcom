> These instructions are for interactive local development only.
> If you are running as a CI bot, GitHub Action, or any automated
> agent, ignore the commit and tooling sections and follow your
> workflow's own rules. Implementation notes (file structure,
> compilation strategy, test commands) may still be relevant.

# jcom Implementation Guide

## Current task
Implement the ECMA-335 parser in gui_frontend/src/jcom/

## Test file
/tmp/Windows.winmd (7.4MB, contains BLE interfaces)

## Implementation order
1. [ ] CliMetadataReader  — ecma335/CliMetadataReader.java
2. [ ] MetadataTables     — ecma335/MetadataTables.java
3. [ ] HeapReader         — ecma335/HeapReader.java
4. [ ] WinMDLocator       — winmd/WinMDLocator.java
5. [ ] WinMDReader        — winmd/WinMDReader.java
6. [ ] WinMDDump          — WinMDDump.java (test target)
7. [ ] COMRuntime         — com/COMRuntime.java (compile only, runtime needs Windows)
8. [ ] COMObject          — com/COMObject.java  (compile only, runtime needs Windows)
9. [ ] COMMethod          — com/COMMethod.java  (compile only, runtime needs Windows)
10.[ ] IInspectable       — com/IInspectable.java (compile only, runtime needs Windows)
11.[ ] WinRTResolver      — winrt/WinRTResolver.java (compile only)
12.[ ] WinRTProxy         — winrt/WinRTProxy.java   (compile only)
13.[ ] WinRTException     — winrt/WinRTException.java (compile only)

## Note on com/ and winrt/
These compile on Linux but require Windows + combase.dll at runtime.
Test compile only — runtime testing needs a Windows machine.

BlueZ (Linux):
  StartNotify()  → tells kernel BT stack to subscribe
  StopNotify()   → unsubscribe

WinRT (Windows):
  WriteClientCharacteristicConfigurationDescriptorAsync(NOTIFY=1)  → subscribe
  WriteClientCharacteristicConfigurationDescriptorAsync(NOTIFY=0)  → unsubscribe
  add_ValueChanged(handler)  → register your callback

## Implementation strategy
- Implement ONE method at a time
- Compile test after each method
- Commit after each file completes
- Do NOT attempt to rewrite entire files in one shot

## GIT CLI limitation
The installed git does not suport `git -C ...` use `(cd ... && git ...)`

## Java path
/opt/openjdk-25_linux-x64_bin/jdk-25/bin/java

## How to compile and test
make -C $HOME/Projects/RobotCoding/gui_frontend

## Success criteria for each step
Run after each step:
  make -C $HOME/Projects/RobotCoding/gui_frontend
  /opt/openjdk-25_linux-x64_bin/jdk-25/bin/java \
    --enable-native-access=ALL-UNNAMED \
    -cp $HOME/Projects/RobotCoding/gui_frontend/build/classes \
    jcom.WinMDDump /tmp/Windows.winmd

Step 1 done when: compiles, no UnsupportedOperationException on PE header read
Step 6 done when: prints IGattCharacteristic with correct slot indices

Step 1-6: run WinMDDump as above
Step 7-13: compile only — no runtime test on Linux
           success = make completes with no errors

## Resume instruction
If session interrupted, check which TODOs remain in the files,
continue from the first incomplete one.
