' MCLauncher - silent start, no console window.
' First run (no .venv yet) falls back to run.bat so the install
' progress is visible; afterwards pythonw starts the app silently.
Set shell = CreateObject("Wscript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
shell.CurrentDirectory = fso.GetParentFolderName(WScript.ScriptFullName)

If fso.FileExists(".venv\Scripts\pythonw.exe") Then
    shell.Run ".venv\Scripts\pythonw.exe main.py", 0, False
Else
    shell.Run "run.bat", 1, True
    shell.Run ".venv\Scripts\pythonw.exe main.py", 0, False
End If
